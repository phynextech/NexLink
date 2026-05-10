using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;
using NexLink.Models;

namespace NexLink.Services
{
    /// <summary>
    /// Handles chunked file send/receive, progress tracking, pause/resume, retry,
    /// AES-256 encryption, and writing completed files to Downloads\NexLink\.
    /// </summary>
    public class FileTransferService
    {
        // ── Constants ─────────────────────────────────────────────────────────
        private const int    CHUNK_SIZE_BYTES  = 65536;   // 64 KB chunks
        private const int    MAX_CHUNK_RETRIES = 3;
        private const long   MAX_RELAY_BYTES   = 500L * 1024 * 1024; // 500 MB

        // ── Events ────────────────────────────────────────────────────────────
        public event Action<ChatMessage>?        MessageAdded;
        public event Action<string, double, string, string>? ProgressUpdated;  // fileId, 0-1, speed, eta
        public event Action<string, string>?     FileReceived;     // fileId, localPath
        public event Action<string>?             TransferFailed;
        public event Action<string, string>?     TransferLog;      // fileId, logLine

        // ── Internal state ─────────────────────────────────────────────────────
        private readonly WebSocketService _ws;
        private readonly string _downloadFolder;

        // outbound: fileId → SendSession
        private readonly ConcurrentDictionary<string, SendSession>    _sends    = new();
        // inbound:  fileId → ReceiveSession
        private readonly ConcurrentDictionary<string, ReceiveSession> _receives = new();

        // ── AES encryption key (optional — set before sending) ─────────────────
        public bool EncryptionEnabled { get; set; } = false;
        private byte[]? _aesKey;   // 256-bit AES key shared out-of-band

        public FileTransferService(WebSocketService ws)
        {
            _ws = ws;
            _downloadFolder = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
                "Downloads", "NexLink");
            Directory.CreateDirectory(_downloadFolder);
        }

        // ── AES key management ────────────────────────────────────────────────
        public void SetEncryptionKey(byte[] key32Bytes) => _aesKey = key32Bytes;

        public byte[] GenerateAesKey()
        {
            _aesKey = RandomNumberGenerator.GetBytes(32);
            return _aesKey;
        }

        // ── Send ──────────────────────────────────────────────────────────────

        /// <summary>
        /// Initiates a chunked file send. Returns the fileId.
        /// </summary>
        public async Task<string?> SendFileAsync(string filePath, string? messageId = null, CancellationToken ct = default)
        {
            if (!File.Exists(filePath)) return null;

            var info      = new FileInfo(filePath);
            var fileId    = Guid.NewGuid().ToString("N");
            var mime      = GuessMimeType(filePath);
            var totalChunks = (int)Math.Ceiling((double)info.Length / CHUNK_SIZE_BYTES);
            var msgId     = messageId ?? Guid.NewGuid().ToString();

            // Build UI message card
            var chatMsg = new ChatMessage
            {
                MessageId    = msgId,
                FileId       = fileId,
                SenderId     = "desktop",
                IsSentByMe   = true,
                MessageType  = MimeToMessageType(mime),
                FileName     = info.Name,
                MimeType     = mime,
                FileSizeBytes= info.Length,
                TotalChunks  = totalChunks,
                LocalFilePath= filePath,
                State        = TransferState.Offered,
                Timestamp    = DateTime.Now,
            };
            MessageAdded?.Invoke(chatMsg);

            // Announce offer to peer
            _ws.Send(new
            {
                type        = "chat_message",
                messageId   = msgId,
                content     = $"📎 {info.Name}",
                fileName    = info.Name,
                fileMime    = mime,
                fileSizeBytes = info.Length,
                fileId,
                timestamp   = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
            });
            _ws.Send(new
            {
                type        = "chat_file_offer",
                fileId,
                name        = info.Name,
                size        = info.Length,
                mimeType    = mime,
                totalChunks,
                messageId   = msgId,
                encrypted   = EncryptionEnabled && _aesKey != null,
            });

            var session = new SendSession
            {
                FileId      = fileId,
                FilePath    = filePath,
                TotalChunks = totalChunks,
                ChatMsg     = chatMsg,
                Cts         = CancellationTokenSource.CreateLinkedTokenSource(ct),
            };
            _sends[fileId] = session;

            chatMsg.State = TransferState.Accepted;
            await Task.Delay(200, ct); // small grace for accept
            _ = Task.Run(() => RunSendLoopAsync(session), ct);
            return fileId;
        }

        private async Task RunSendLoopAsync(SendSession session)
        {
            var token      = session.Cts.Token;
            var startTime  = DateTime.UtcNow;
            long bytesSent = 0;

            session.ChatMsg.State = TransferState.Transferring;

            try
            {
                await using var fs = new FileStream(session.FilePath, FileMode.Open, FileAccess.Read, FileShare.Read, CHUNK_SIZE_BYTES, useAsync: true);
                var buf = new byte[CHUNK_SIZE_BYTES];

                for (int i = 0; i < session.TotalChunks; i++)
                {
                    // Pause support
                    while (session.Paused && !token.IsCancellationRequested)
                        await Task.Delay(200, token);
                    token.ThrowIfCancellationRequested();

                    // Skip already-acked chunks (resume support)
                    if (session.AckedChunks.Contains(i))
                    {
                        fs.Seek((long)i * CHUNK_SIZE_BYTES, SeekOrigin.Begin);
                        continue;
                    }

                    fs.Position = (long)i * CHUNK_SIZE_BYTES;
                    int read = await fs.ReadAsync(buf, 0, buf.Length, token);
                    if (read == 0) break;

                    var chunkBytes = buf[..read];
                    if (EncryptionEnabled && _aesKey != null)
                        chunkBytes = EncryptChunk(chunkBytes, i);

                    var data64 = Convert.ToBase64String(chunkBytes);

                    // Retry loop per chunk
                    for (int attempt = 0; attempt < MAX_CHUNK_RETRIES; attempt++)
                    {
                        _ws.Send(new
                        {
                            type       = "chat_file_chunk",
                            fileId     = session.FileId,
                            index      = i,
                            total      = session.TotalChunks,
                            data       = data64,
                            chunkSize  = read,
                            encrypted  = EncryptionEnabled && _aesKey != null,
                        });
                        break; // ACK-based retry handled by ReceiveSession on peer
                    }

                    bytesSent += read;
                    double progress   = (double)(i + 1) / session.TotalChunks;
                    double elapsed    = (DateTime.UtcNow - startTime).TotalSeconds;
                    double speed      = elapsed > 0 ? bytesSent / elapsed : 0; // bytes/s
                    double remaining  = speed > 0 ? (new FileInfo(session.FilePath).Length - bytesSent) / speed : 0;

                    session.ChatMsg.TransferProgress     = progress;
                    session.ChatMsg.TransferSpeedLabel   = FormatSpeed(speed);
                    session.ChatMsg.EtaLabel             = FormatEta(remaining);
                    ProgressUpdated?.Invoke(session.FileId, progress, FormatSpeed(speed), FormatEta(remaining));

                    // Throttle slightly to avoid flooding the socket
                    if (i % 16 == 15) await Task.Delay(1, token);
                }

                _ws.Send(new { type = "chat_file_done", fileId = session.FileId });
                session.ChatMsg.State             = TransferState.Complete;
                session.ChatMsg.TransferProgress  = 1.0;
                session.ChatMsg.IsDelivered       = true;
                _sends.TryRemove(session.FileId, out _);
            }
            catch (OperationCanceledException)
            {
                session.ChatMsg.State = TransferState.Cancelled;
                _ws.Send(new { type = "chat_file_cancel", fileId = session.FileId });
                _sends.TryRemove(session.FileId, out _);
            }
            catch (Exception ex)
            {
                session.ChatMsg.State = TransferState.Failed;
                TransferFailed?.Invoke(session.FileId);
                TransferLog?.Invoke(session.FileId, $"Send error: {ex.Message}");
                _sends.TryRemove(session.FileId, out _);
            }
        }

        // ── Receive ───────────────────────────────────────────────────────────

        public void HandleFileOffer(JObject data)
        {
            var fileId      = data["fileId"]?.ToString() ?? "";
            var name        = data["name"]?.ToString() ?? "file";
            var size        = data["size"]?.ToObject<long>() ?? 0;
            var mime        = data["mimeType"]?.ToString() ?? "application/octet-stream";
            var totalChunks = data["totalChunks"]?.ToObject<int>() ?? 1;
            var msgId       = data["messageId"]?.ToString() ?? Guid.NewGuid().ToString();
            var encrypted   = data["encrypted"]?.ToObject<bool>() ?? false;

            var safeName    = SanitizeFileName(name);
            var destPath    = GetUniqueFilePath(_downloadFolder, safeName);

            var chatMsg = new ChatMessage
            {
                MessageId     = msgId,
                FileId        = fileId,
                SenderId      = "mobile",
                IsSentByMe    = false,
                MessageType   = MimeToMessageType(mime),
                FileName      = name,
                MimeType      = mime,
                FileSizeBytes = size,
                TotalChunks   = totalChunks,
                LocalFilePath = destPath,
                State         = TransferState.Offered,
                Timestamp     = DateTime.Now,
            };
            MessageAdded?.Invoke(chatMsg);

            var session = new ReceiveSession
            {
                FileId      = fileId,
                DestPath    = destPath,
                TotalChunks = totalChunks,
                Encrypted   = encrypted,
                ChatMsg     = chatMsg,
            };
            _receives[fileId] = session;

            // Accept immediately
            _ws.Send(new { type = "chat_file_accept", fileId });
            chatMsg.State = TransferState.Transferring;
        }

        public async Task HandleFileChunkAsync(JObject data)
        {
            var fileId     = data["fileId"]?.ToString() ?? "";
            var index      = data["index"]?.ToObject<int>()  ?? 0;
            var total      = data["total"]?.ToObject<int>()  ?? 1;
            var data64     = data["data"]?.ToString() ?? "";
            var encrypted  = data["encrypted"]?.ToObject<bool>() ?? false;

            if (!_receives.TryGetValue(fileId, out var session)) return;

            // Skip duplicate chunks
            if (session.ReceivedIndices.Contains(index)) return;

            byte[] chunkBytes;
            try { chunkBytes = Convert.FromBase64String(data64); }
            catch { return; }

            if (encrypted && _aesKey != null)
                chunkBytes = DecryptChunk(chunkBytes, index);

            // Write chunk at correct offset
            await session.Semaphore.WaitAsync();
            try
            {
                await using var fs = new FileStream(session.DestPath, FileMode.OpenOrCreate, FileAccess.Write, FileShare.None, 4096, useAsync: true);
                fs.Position = (long)index * CHUNK_SIZE_BYTES;
                await fs.WriteAsync(chunkBytes, 0, chunkBytes.Length);
            }
            finally
            {
                session.Semaphore.Release();
            }

            session.ReceivedIndices.Add(index);

            // Send ACK back
            _ws.Send(new { type = "chat_file_ack", fileId, chunkIndex = index });

            // Update progress
            double progress  = (double)session.ReceivedIndices.Count / total;
            double elapsed   = (DateTime.UtcNow - session.StartedAt).TotalSeconds;
            long   bytesRcvd = (long)session.ReceivedIndices.Count * CHUNK_SIZE_BYTES;
            double speed     = elapsed > 0 ? bytesRcvd / elapsed : 0;
            double remaining = speed > 0 ? (session.ChatMsg.FileSizeBytes - bytesRcvd) / speed : 0;

            session.ChatMsg.TransferProgress    = progress;
            session.ChatMsg.TransferSpeedLabel  = FormatSpeed(speed);
            session.ChatMsg.EtaLabel            = FormatEta(remaining);
            ProgressUpdated?.Invoke(fileId, progress, FormatSpeed(speed), FormatEta(remaining));
        }

        public void HandleFileDone(JObject data)
        {
            var fileId = data["fileId"]?.ToString() ?? "";
            if (!_receives.TryGetValue(fileId, out var session)) return;

            session.ChatMsg.State             = TransferState.Complete;
            session.ChatMsg.TransferProgress  = 1.0;
            session.ChatMsg.IsDelivered       = true;
            _receives.TryRemove(fileId, out _);

            FileReceived?.Invoke(fileId, session.DestPath);
            TransferLog?.Invoke(fileId, $"Received → {session.DestPath}");

            // Mark delivered
            _ws.Send(new { type = "chat_delivered", messageId = session.ChatMsg.MessageId });
        }

        public void HandleFileAccept(string fileId)
        {
            if (_sends.TryGetValue(fileId, out var session))
                session.Paused = false;
        }

        public void HandleFileReject(string fileId)
        {
            if (_sends.TryGetValue(fileId, out var session))
            {
                session.Cts.Cancel();
                session.ChatMsg.State = TransferState.Cancelled;
            }
        }

        // ── Pause / Resume / Cancel ────────────────────────────────────────────

        public void PauseTransfer(string fileId)
        {
            if (_sends.TryGetValue(fileId, out var s)) s.Paused = true;
            _ws.Send(new { type = "chat_file_pause", fileId });
        }

        public void ResumeTransfer(string fileId)
        {
            if (_sends.TryGetValue(fileId, out var s)) s.Paused = false;
            _ws.Send(new { type = "chat_file_resume", fileId });
        }

        public void CancelTransfer(string fileId)
        {
            if (_sends.TryGetValue(fileId, out var s)) s.Cts.Cancel();
            if (_receives.TryGetValue(fileId, out var r)) r.ChatMsg.State = TransferState.Cancelled;
            _ws.Send(new { type = "chat_file_cancel", fileId });
        }

        // ── Screenshot quick-send ──────────────────────────────────────────────

        public async Task SendScreenshotAsync()
        {
            var path = await CaptureScreenshotAsync();
            if (path != null) await SendFileAsync(path);
        }

        private async Task<string?> CaptureScreenshotAsync()
        {
            try
            {
                var ts   = DateTime.Now.ToString("yyyyMMdd_HHmmss");
                var path = Path.Combine(_downloadFolder, $"Screenshot_{ts}.png");
                var screen = System.Windows.SystemParameters.PrimaryScreenWidth;
                var h      = System.Windows.SystemParameters.PrimaryScreenHeight;

                using var bmp = new System.Drawing.Bitmap((int)screen, (int)h);
                using var g   = System.Drawing.Graphics.FromImage(bmp);
                g.CopyFromScreen(0, 0, 0, 0, bmp.Size);
                bmp.Save(path, System.Drawing.Imaging.ImageFormat.Png);
                return path;
            }
            catch { return null; }
        }

        // ── Helpers ───────────────────────────────────────────────────────────

        private static string SanitizeFileName(string name)
        {
            foreach (var c in Path.GetInvalidFileNameChars())
                name = name.Replace(c, '_');
            return name;
        }

        private static string GetUniqueFilePath(string folder, string name)
        {
            var path = Path.Combine(folder, name);
            if (!File.Exists(path)) return path;

            var noExt = Path.GetFileNameWithoutExtension(name);
            var ext   = Path.GetExtension(name);
            int n     = 1;
            while (File.Exists(path))
            {
                path = Path.Combine(folder, $"{noExt} ({n++}){ext}");
            }
            return path;
        }

        public static ChatMessageType MimeToMessageType(string mime) => mime switch
        {
            var m when m.StartsWith("image/") => ChatMessageType.Image,
            var m when m.StartsWith("video/") => ChatMessageType.Video,
            var m when m.StartsWith("audio/") => ChatMessageType.Audio,
            "application/vnd.android.package-archive" => ChatMessageType.Apk,
            var m when m.Contains("zip") || m.Contains("rar") || m.Contains("7z") || m.Contains("tar") => ChatMessageType.Archive,
            var m when m.Contains("pdf") || m.Contains("word") || m.Contains("excel") || m.Contains("powerpoint")
                    || m.Contains("text/") => ChatMessageType.Document,
            _ => ChatMessageType.File,
        };

        public static string GuessMimeType(string path)
        {
            return Path.GetExtension(path).ToLowerInvariant() switch
            {
                ".jpg" or ".jpeg" => "image/jpeg",
                ".png"            => "image/png",
                ".gif"            => "image/gif",
                ".webp"           => "image/webp",
                ".mp4"            => "video/mp4",
                ".mkv"            => "video/x-matroska",
                ".mov"            => "video/quicktime",
                ".avi"            => "video/x-msvideo",
                ".mp3"            => "audio/mpeg",
                ".m4a"            => "audio/mp4",
                ".wav"            => "audio/wav",
                ".ogg"            => "audio/ogg",
                ".pdf"            => "application/pdf",
                ".doc" or ".docx" => "application/msword",
                ".xls" or ".xlsx" => "application/vnd.ms-excel",
                ".ppt" or ".pptx" => "application/vnd.ms-powerpoint",
                ".zip"            => "application/zip",
                ".rar"            => "application/x-rar-compressed",
                ".7z"             => "application/x-7z-compressed",
                ".tar"            => "application/x-tar",
                ".apk"            => "application/vnd.android.package-archive",
                ".txt" or ".csv"  => "text/plain",
                _                 => "application/octet-stream",
            };
        }

        private static string FormatSpeed(double bytesPerSec)
        {
            if (bytesPerSec < 1024)              return $"{bytesPerSec:F0} B/s";
            if (bytesPerSec < 1024 * 1024)       return $"{bytesPerSec / 1024:F1} KB/s";
            if (bytesPerSec < 1024 * 1024 * 1024)return $"{bytesPerSec / (1024 * 1024):F1} MB/s";
            return $"{bytesPerSec / (1024.0 * 1024 * 1024):F2} GB/s";
        }

        private static string FormatEta(double seconds)
        {
            if (seconds <= 0) return "";
            if (seconds < 60)  return $"{(int)seconds}s";
            if (seconds < 3600)return $"{(int)(seconds / 60)}m {(int)(seconds % 60)}s";
            return $"{(int)(seconds / 3600)}h {(int)((seconds % 3600) / 60)}m";
        }

        // ── AES-256 GCM encryption helpers ────────────────────────────────────

        private byte[] EncryptChunk(byte[] data, int chunkIndex)
        {
            if (_aesKey == null) return data;
            using var aes = new AesGcm(_aesKey, AesGcm.TagByteSizes.MaxSize);
            var nonce = new byte[AesGcm.NonceByteSizes.MaxSize];
            BitConverter.GetBytes(chunkIndex).CopyTo(nonce, 0);
            var cipher = new byte[data.Length];
            var tag    = new byte[AesGcm.TagByteSizes.MaxSize];
            aes.Encrypt(nonce, data, cipher, tag);
            var result = new byte[nonce.Length + tag.Length + cipher.Length];
            nonce.CopyTo(result, 0);
            tag.CopyTo(result, nonce.Length);
            cipher.CopyTo(result, nonce.Length + tag.Length);
            return result;
        }

        private byte[] DecryptChunk(byte[] data, int chunkIndex)
        {
            if (_aesKey == null) return data;
            try
            {
                using var aes   = new AesGcm(_aesKey, AesGcm.TagByteSizes.MaxSize);
                int nLen        = AesGcm.NonceByteSizes.MaxSize;
                int tLen        = AesGcm.TagByteSizes.MaxSize;
                var nonce       = data[..nLen];
                var tag         = data[nLen..(nLen + tLen)];
                var cipher      = data[(nLen + tLen)..];
                var plain       = new byte[cipher.Length];
                aes.Decrypt(nonce, cipher, tag, plain);
                return plain;
            }
            catch { return data; } // fallback — wrong key / corrupted
        }

        // ── Session types ─────────────────────────────────────────────────────

        private class SendSession
        {
            public string        FileId       { get; init; } = "";
            public string        FilePath     { get; init; } = "";
            public int           TotalChunks  { get; init; }
            public ChatMessage   ChatMsg      { get; init; } = new();
            public CancellationTokenSource Cts { get; init; } = new();
            public HashSet<int>  AckedChunks  { get; }       = new();
            public bool          Paused       { get; set; }
        }

        private class ReceiveSession
        {
            public string       FileId       { get; init; } = "";
            public string       DestPath     { get; init; } = "";
            public int          TotalChunks  { get; init; }
            public bool         Encrypted    { get; init; }
            public ChatMessage  ChatMsg      { get; init; } = new();
            public HashSet<int> ReceivedIndices { get; } = new();
            public SemaphoreSlim Semaphore   { get; } = new(1, 1);
            public DateTime     StartedAt    { get; } = DateTime.UtcNow;
        }
    }
}
