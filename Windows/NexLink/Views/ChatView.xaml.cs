using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Threading;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;
using NexLink.Models;
using NexLink.Services;

namespace NexLink.Views
{
    public partial class ChatView : UserControl
    {
        // ── Public collections ──────────────────────────────────────────────
        public ObservableCollection<ChatMessage> Messages    { get; } = new();
        public ObservableCollection<ChatMessage> AllMessages { get; } = new();

        // ── Injected services ────────────────────────────────────────────────
        public WebSocketService?    WsService { private get; set; }
        public FileTransferService? FtService { private get; set; }

        private MediaPlayer _audioPlayer = new();
        private string?     _playingPath;
        private Timer?      _typingTimer;
        private bool        _isPeerOnline;

        // ── Local persistence ────────────────────────────────────────────────
        private static readonly string _cacheDir =
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                         "NexLink", "Chat");
        private static readonly string _cacheFile = Path.Combine(_cacheDir, "history.json");

        public ChatView()
        {
            InitializeComponent();
            ChatMessagesList.ItemsSource = Messages;
            // Load from local cache immediately so history is visible before network
            LoadLocalCache();
        }

        // ── Initialize (called by MainWindow once services are ready) ─────────
        public void Initialize(WebSocketService ws, FileTransferService ft)
        {
            WsService = ws;
            FtService  = ft;

            ft.MessageAdded   += OnMessageAdded;
            ft.FileReceived   += OnFileReceived;
            ft.TransferFailed += fid => Dispatcher.Invoke(() =>
            {
                var msg = Messages.FirstOrDefault(m => m.FileId == fid);
                if (msg != null) msg.State = TransferState.Failed;
            });
        }

        // ── Local JSON cache ─────────────────────────────────────────────────
        private void LoadLocalCache()
        {
            try
            {
                if (!File.Exists(_cacheFile)) return;
                var json = File.ReadAllText(_cacheFile);
                var items = JsonConvert.DeserializeObject<List<ChatMessageDto>>(json);
                if (items == null) return;
                Messages.Clear(); AllMessages.Clear();
                foreach (var dto in items.OrderBy(x => x.Timestamp))
                {
                    var msg = DtoToMessage(dto);
                    Messages.Add(msg);
                    AllMessages.Add(msg);
                }
                Dispatcher.InvokeAsync(ScrollToBottom);
            }
            catch { /* corrupt cache — ignore */ }
        }

        private void SaveLocalCache()
        {
            try
            {
                Directory.CreateDirectory(_cacheDir);
                // Keep last 300 messages
                var dtos = AllMessages
                    .OrderBy(m => m.Timestamp)
                    .TakeLast(300)
                    .Select(MessageToDto)
                    .ToList();
                File.WriteAllText(_cacheFile, JsonConvert.SerializeObject(dtos, Formatting.None));
            }
            catch { }
        }

        // ── DTO helpers ──────────────────────────────────────────────────────
        private sealed class ChatMessageDto
        {
            public string   MessageId    { get; set; } = "";
            public string   SenderId     { get; set; } = "";
            public bool     IsSentByMe   { get; set; }
            public string   MessageType  { get; set; } = "Text";
            public string   Content      { get; set; } = "";
            public string?  FileId       { get; set; }
            public string?  FileName     { get; set; }
            public string?  MimeType     { get; set; }
            public long     FileSizeBytes{ get; set; }
            public bool     IsDelivered  { get; set; }
            public bool     IsRead       { get; set; }
            public bool     IsStarred    { get; set; }
            public string?  Reaction     { get; set; }
            public long     Timestamp    { get; set; }
        }

        private static ChatMessageDto MessageToDto(ChatMessage m) => new()
        {
            MessageId     = m.MessageId,
            SenderId      = m.SenderId,
            IsSentByMe    = m.IsSentByMe,
            MessageType   = m.MessageType.ToString(),
            Content       = m.Content,
            FileId        = m.FileId,
            FileName      = m.FileName,
            MimeType      = m.MimeType,
            FileSizeBytes = m.FileSizeBytes,
            IsDelivered   = m.IsDelivered,
            IsRead        = m.IsRead,
            IsStarred     = m.IsStarred,
            Reaction      = m.Reaction,
            Timestamp     = new DateTimeOffset(m.Timestamp).ToUnixTimeMilliseconds(),
        };

        private static ChatMessage DtoToMessage(ChatMessageDto d) => new()
        {
            MessageId     = d.MessageId,
            SenderId      = d.SenderId,
            IsSentByMe    = d.IsSentByMe,
            MessageType   = Enum.TryParse<ChatMessageType>(d.MessageType, true, out var t) ? t : ChatMessageType.Text,
            Content       = d.Content,
            FileId        = d.FileId,
            FileName      = d.FileName,
            MimeType      = d.MimeType ?? "",
            FileSizeBytes = d.FileSizeBytes,
            IsDelivered   = d.IsDelivered,
            IsRead        = d.IsRead,
            IsStarred     = d.IsStarred,
            Reaction      = d.Reaction,
            State         = TransferState.Complete,
            Timestamp     = DateTimeOffset.FromUnixTimeMilliseconds(d.Timestamp).LocalDateTime,
        };

        // ── Handle incoming events from Socket.IO (called by MainWindow) ─────
        public void HandleIncomingEvent(string eventName, JObject data)
        {
            Dispatcher.InvokeAsync(() =>
            {
                switch (eventName)
                {
                    case "chat_message":     HandleChatMessage(data);   break;
                    case "chat_file_offer":  FtService?.HandleFileOffer(data); break;
                    case "chat_file_chunk":  _ = FtService?.HandleFileChunkAsync(data); break;
                    case "chat_file_done":   FtService?.HandleFileDone(data);  break;
                    case "chat_file_accept": FtService?.HandleFileAccept(data["fileId"]?.ToString() ?? ""); break;
                    case "chat_file_reject": FtService?.HandleFileReject(data["fileId"]?.ToString() ?? ""); break;
                    case "chat_file_pause":
                        SetMessageState(data["fileId"]?.ToString(), TransferState.Paused); break;
                    case "chat_file_resume":
                        SetMessageState(data["fileId"]?.ToString(), TransferState.Transferring); break;
                    case "chat_typing":
                        ShowTypingIndicator(data["isTyping"]?.ToObject<bool>() ?? false); break;
                    case "chat_delivered":
                        SetDelivered(data["messageId"]?.ToString()); break;
                    case "chat_read":
                        SetRead(data["messageId"]?.ToString()); break;
                    case "chat_reaction":
                        SetReaction(data["messageId"]?.ToString(), data["emoji"]?.ToString()); break;
                    case "chat_history":     HandleHistory(data);    break;
                    case "chat_star":
                        SetStarred(data["messageId"]?.ToString(), data["starred"]?.ToObject<bool>() ?? false); break;
                    case "chat_clipboard":   HandleClipboardMsg(data); break;
                    case "chat_screenshot":  HandleScreenshotMsg(data); break;
                    case "peer_online":      SetPeerOnline(true);  break;
                    case "peer_offline":     SetPeerOnline(false); break;
                }
            });
        }

        // ── Peer online indicator ────────────────────────────────────────────
        public void SetPeerOnline(bool online)
        {
            _isPeerOnline = online;
            Dispatcher.InvokeAsync(() =>
            {
                ChatOnlineDot.Fill        = new SolidColorBrush(online ? Color.FromRgb(0x2C, 0xA5, 0x67) : Color.FromRgb(0x60, 0x60, 0x80));
                ChatStatusLabel.Text      = online ? "Online" : "Offline";
                ChatStatusLabel.Foreground= new SolidColorBrush(online ? Color.FromRgb(0x2C, 0xA5, 0x67) : Color.FromRgb(0x80, 0x80, 0x99));
            });
        }

        // ── Incoming chat_message ────────────────────────────────────────────
        private void HandleChatMessage(JObject data)
        {
            var msgId   = data["messageId"]?.ToString() ?? Guid.NewGuid().ToString();
            var content = data["content"]?.ToString() ?? "";
            var fileId  = data["fileId"]?.ToString();
            var mime    = data["fileMime"]?.ToString() ?? "";
            var fname   = data["fileName"]?.ToString();
            var fsize   = data["fileSizeBytes"]?.ToObject<long>() ?? 0;
            var ts      = data["timestamp"]?.ToObject<long>() ?? DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

            if (Messages.Any(m => m.MessageId == msgId)) return;

            var type = fname != null ? FileTransferService.MimeToMessageType(mime) : ChatMessageType.Text;

            var msg = new ChatMessage
            {
                MessageId     = msgId,
                SenderId      = "mobile",
                IsSentByMe    = false,
                MessageType   = type,
                Content       = content,
                FileId        = fileId,
                FileName      = fname,
                MimeType      = mime,
                FileSizeBytes = fsize,
                Timestamp     = DateTimeOffset.FromUnixTimeMilliseconds(ts).LocalDateTime,
                State         = fileId != null ? TransferState.Offered : TransferState.None,
            };

            AddMessage(msg, persist: true);
            WsService?.Send(new { type = "chat_delivered", messageId = msgId });
        }

        private void HandleHistory(JObject data)
        {
            var msgs = data["messages"] as JArray;
            if (msgs == null || msgs.Count == 0) return; // Never wipe local cache on empty server response

            Dispatcher.InvokeAsync(() =>
            {
                // Build a lookup of what's already shown locally
                var existing = AllMessages.ToDictionary(m => m.MessageId, m => m);

                foreach (var m in msgs.OrderBy(x => x["timestamp"]?.ToObject<long>() ?? 0))
                {
                    var msgId = m["messageId"]?.ToString() ?? Guid.NewGuid().ToString();

                    if (existing.TryGetValue(msgId, out var local))
                    {
                        // Merge delivery/read state only — keep local TransferState and file paths
                        local.IsDelivered = m["isDelivered"]?.ToObject<bool>() ?? local.IsDelivered;
                        local.IsRead      = m["isRead"]?.ToObject<bool>()      ?? local.IsRead;
                        local.IsStarred   = m["isStarred"]?.ToObject<bool>()   ?? local.IsStarred;
                        if (m["reaction"]?.ToString() is string r && !string.IsNullOrEmpty(r))
                            local.Reaction = r;
                    }
                    else
                    {
                        // New message from server not in local list — add it
                        var chatMsg = new ChatMessage
                        {
                            MessageId     = msgId,
                            SenderId      = m["senderId"]?.ToString() ?? "",
                            IsSentByMe    = m["senderId"]?.ToString() == "desktop",
                            MessageType   = Enum.TryParse<ChatMessageType>(m["type"]?.ToString(), true, out var t) ? t : ChatMessageType.Text,
                            Content       = m["content"]?.ToString() ?? "",
                            FileName      = m["fileName"]?.ToString(),
                            MimeType      = m["fileMime"]?.ToString() ?? "",
                            FileSizeBytes = m["fileSizeBytes"]?.ToObject<long>() ?? 0,
                            FileId        = m["fileId"]?.ToString(),
                            IsDelivered   = m["isDelivered"]?.ToObject<bool>() ?? false,
                            IsRead        = m["isRead"]?.ToObject<bool>()      ?? false,
                            IsStarred     = m["isStarred"]?.ToObject<bool>()   ?? false,
                            Reaction      = m["reaction"]?.ToString(),
                            State         = TransferState.Complete,
                            Timestamp     = DateTimeOffset.FromUnixTimeMilliseconds(
                                              m["timestamp"]?.ToObject<long>() ?? 0).LocalDateTime,
                        };
                        AddMessage(chatMsg, persist: false); // don't re-persist during merge
                    }
                }

                SaveLocalCache(); // Save merged result once
                ScrollToBottom();
            });
        }

        private void HandleClipboardMsg(JObject data)
        {
            var text = data["text"]?.ToString() ?? "";
            var msg  = new ChatMessage
            {
                MessageId  = Guid.NewGuid().ToString(),
                SenderId   = "mobile",
                IsSentByMe = false,
                MessageType= ChatMessageType.Clipboard,
                Content    = $"📋 {text}",
                Timestamp  = DateTime.Now,
                State      = TransferState.None,
            };
            AddMessage(msg, persist: true);
        }

        private void HandleScreenshotMsg(JObject data) { /* Handled as image offer by FtService */ }

        // ── Message list management ──────────────────────────────────────────
        private void OnMessageAdded(ChatMessage msg)
        {
            Dispatcher.InvokeAsync(() => AddMessage(msg, persist: true));
        }

        private void AddMessage(ChatMessage msg, bool persist = false)
        {
            Messages.Add(msg);
            AllMessages.Add(msg);
            ScrollToBottom();
            if (persist) SaveLocalCache();
        }

        private void ScrollToBottom()
        {
            ChatScrollViewer.ScrollToEnd();
        }

        // ── File received ────────────────────────────────────────────────────
        private void OnFileReceived(string fileId, string localPath)
        {
            Dispatcher.InvokeAsync(() =>
            {
                var msg = Messages.FirstOrDefault(m => m.FileId == fileId);
                if (msg != null)
                {
                    msg.LocalFilePath = localPath;
                    msg.State         = TransferState.Complete;
                    if (msg.MessageType == ChatMessageType.Image && File.Exists(localPath))
                    {
                        var bmp = new BitmapImage();
                        bmp.BeginInit();
                        bmp.UriSource     = new Uri(localPath);
                        bmp.DecodePixelWidth = 400;
                        bmp.CacheOption   = BitmapCacheOption.OnLoad;
                        bmp.EndInit();
                        msg.Thumbnail = bmp;
                    }
                }
                ShowFileToast(localPath);
                SaveLocalCache();
            });
        }

        private void ShowFileToast(string path)
        {
            var fname = Path.GetFileName(path);
            Application.Current.MainWindow?.Dispatcher.InvokeAsync(() =>
            {
                if (Application.Current.MainWindow is MainWindow mw)
                    mw.ShowNotificationPopup("📁 File Received", $"{fname} saved to Downloads\\NexLink");
            });
        }

        // ── Delivery / read / star helpers ───────────────────────────────────
        private void SetMessageState(string? fileId, TransferState state)
        {
            if (fileId == null) return;
            var msg = Messages.FirstOrDefault(m => m.FileId == fileId);
            if (msg != null) msg.State = state;
        }

        private void SetDelivered(string? msgId)
        {
            if (msgId == null) return;
            var msg = Messages.FirstOrDefault(m => m.MessageId == msgId);
            if (msg != null) { msg.IsDelivered = true; SaveLocalCache(); }
        }

        private void SetRead(string? msgId)
        {
            if (msgId == null) return;
            var msg = Messages.FirstOrDefault(m => m.MessageId == msgId);
            if (msg != null) { msg.IsRead = true; SaveLocalCache(); }
        }

        private void SetReaction(string? msgId, string? emoji)
        {
            if (msgId == null) return;
            var msg = Messages.FirstOrDefault(m => m.MessageId == msgId);
            if (msg != null) { msg.Reaction = emoji; SaveLocalCache(); }
        }

        private void SetStarred(string? msgId, bool starred)
        {
            if (msgId == null) return;
            var msg = Messages.FirstOrDefault(m => m.MessageId == msgId);
            if (msg != null) { msg.IsStarred = starred; SaveLocalCache(); }
        }

        // ── Typing indicator ─────────────────────────────────────────────────
        private void ShowTypingIndicator(bool visible)
        {
            TypingIndicatorRow.Visibility = visible ? Visibility.Visible : Visibility.Collapsed;
        }

        // ════════════════════════════════════════════════════════════════════
        // SEND ACTIONS
        // ════════════════════════════════════════════════════════════════════

        private void ChatSend_Click(object sender, RoutedEventArgs e) => SendText();

        private void ChatInput_KeyDown(object sender, KeyEventArgs e)
        {
            if (e.Key == Key.Enter && Keyboard.Modifiers == ModifierKeys.None)
            {
                e.Handled = true;
                SendText();
            }
        }

        private void ChatInput_TextChanged(object sender, TextChangedEventArgs e)
        {
            WsService?.Send(new { type = "chat_typing", isTyping = ChatInputBox.Text.Length > 0 });
            _typingTimer?.Dispose();
            if (ChatInputBox.Text.Length > 0)
            {
                _typingTimer = new Timer(_ =>
                {
                    WsService?.Send(new { type = "chat_typing", isTyping = false });
                }, null, 3000, Timeout.Infinite);
            }
        }

        private void SendText()
        {
            var text = ChatInputBox.Text.Trim();
            if (string.IsNullOrEmpty(text)) return;

            var msgId = Guid.NewGuid().ToString();
            var msg   = new ChatMessage
            {
                MessageId  = msgId,
                SenderId   = "desktop",
                IsSentByMe = true,
                MessageType= ChatMessageType.Text,
                Content    = text,
                Timestamp  = DateTime.Now,
                State      = TransferState.None,
            };
            AddMessage(msg, persist: true);

            WsService?.Send(new
            {
                type      = "chat_message",
                messageId = msgId,
                content   = text,
                timestamp = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
            });

            ChatInputBox.Clear();
            WsService?.Send(new { type = "chat_typing", isTyping = false });
        }

        private void ChatAttach_Click(object sender, RoutedEventArgs e)
        {
            var dlg = new Microsoft.Win32.OpenFileDialog
            {
                Title       = "Select file(s) to send",
                Filter      = "All Files (*.*)|*.*",
                Multiselect = true,
            };
            if (dlg.ShowDialog() == true)
                foreach (var path in dlg.FileNames)
                    _ = FtService?.SendFileAsync(path);
        }

        private void ChatClipboard_Click(object sender, RoutedEventArgs e)
        {
            var text = Clipboard.GetText();
            if (string.IsNullOrEmpty(text)) return;
            WsService?.Send(new { type = "chat_clipboard", text });
            AddMessage(new ChatMessage
            {
                MessageId  = Guid.NewGuid().ToString(),
                SenderId   = "desktop",
                IsSentByMe = true,
                MessageType= ChatMessageType.Clipboard,
                Content    = $"📋 {text}",
                Timestamp  = DateTime.Now,
            }, persist: true);
        }

        private async void ChatScreenshot_Click(object sender, RoutedEventArgs e)
        {
            if (FtService != null) await FtService.SendScreenshotAsync();
        }

        private void ChatVoice_Click(object sender, RoutedEventArgs e)
        {
            MessageBox.Show("Voice recording coming soon!", "NexLink", MessageBoxButton.OK, MessageBoxImage.Information);
        }

        // ── Drag-drop ────────────────────────────────────────────────────────
        private void Chat_DragEnter(object sender, DragEventArgs e)
        {
            if (e.Data.GetDataPresent(DataFormats.FileDrop))
            {
                DragDropOverlay.Visibility = Visibility.Visible;
                e.Effects = DragDropEffects.Copy;
                e.Handled = true;
            }
        }

        private void Chat_DragLeave(object sender, DragEventArgs e)
        {
            DragDropOverlay.Visibility = Visibility.Collapsed;
        }

        private void Chat_Drop(object sender, DragEventArgs e)
        {
            DragDropOverlay.Visibility = Visibility.Collapsed;
            if (e.Data.GetData(DataFormats.FileDrop) is string[] files)
                foreach (var f in files)
                    _ = FtService?.SendFileAsync(f);
        }

        // ── Per-message actions ──────────────────────────────────────────────
        private void ChatPause_Click(object sender, RoutedEventArgs e)
        {
            if (sender is Button btn && btn.Tag is string fileId)
                FtService?.PauseTransfer(fileId);
        }

        private void ChatDownload_Click(object sender, RoutedEventArgs e)
        {
            if (sender is Button btn && btn.Tag is ChatMessage msg && msg.LocalFilePath != null)
                Process.Start(new ProcessStartInfo(msg.LocalFilePath) { UseShellExecute = true });
        }

        private void ChatOpenFolder_Click(object sender, RoutedEventArgs e)
        {
            if (sender is Button btn && btn.Tag is string path && File.Exists(path))
                Process.Start("explorer.exe", $"/select,\"{path}\"");
        }

        private void ChatImage_Click(object sender, MouseButtonEventArgs e)
        {
            if (sender is Image img && img.Source is BitmapImage bmp)
            {
                var w = new Window
                {
                    Title              = "Image Preview — NexLink",
                    Background         = new SolidColorBrush(Color.FromArgb(0xF0, 0x08, 0x08, 0x14)),
                    WindowStyle        = WindowStyle.ToolWindow,
                    Width              = 800, Height = 600,
                    ResizeMode         = ResizeMode.CanResize,
                    WindowStartupLocation = WindowStartupLocation.CenterScreen,
                };
                w.Content = new Image { Source = bmp, Stretch = Stretch.Uniform };
                w.Show();
            }
        }

        private void ChatPlay_Click(object sender, RoutedEventArgs e)
        {
            if (sender is Button btn && btn.Tag is string path && File.Exists(path))
            {
                if (_playingPath == path)
                {
                    _audioPlayer.Stop();
                    _playingPath = null;
                }
                else
                {
                    _audioPlayer.Open(new Uri(path));
                    _audioPlayer.Play();
                    _playingPath = path;
                }
            }
        }

        // ── Search ───────────────────────────────────────────────────────────
        private void ChatSearch_Changed(object sender, TextChangedEventArgs e)
        {
            var q = ChatSearchBox.Text.Trim().ToLowerInvariant();
            Messages.Clear();
            foreach (var m in AllMessages)
            {
                if (string.IsNullOrEmpty(q)
                    || m.Content.Contains(q, StringComparison.OrdinalIgnoreCase)
                    || (m.FileName?.Contains(q, StringComparison.OrdinalIgnoreCase) ?? false))
                    Messages.Add(m);
            }
        }

        private void ChatSearchToggle_Click(object sender, RoutedEventArgs e)
        {
            var bar = SearchBarRow;
            bar.Visibility = bar.Visibility == Visibility.Visible
                ? Visibility.Collapsed
                : Visibility.Visible;
            if (bar.Visibility == Visibility.Visible)
                ChatSearchBox.Focus();
            else
            {
                ChatSearchBox.Text = "";
                Messages.Clear();
                foreach (var m in AllMessages) Messages.Add(m);
            }
        }

        private void ChatStarred_Click(object sender, RoutedEventArgs e)
        {
            var starred = AllMessages.Where(m => m.IsStarred).ToList();
            ChatSearchBox.Text = "⭐";
            Messages.Clear();
            foreach (var m in starred) Messages.Add(m);
        }

        private void ChatHistory_Click(object sender, RoutedEventArgs e)
        {
            WsService?.Send(new { type = "chat_history_req", limit = 200 });
        }

        // ── Public API ───────────────────────────────────────────────────────
        public void RequestHistory()
        {
            WsService?.Send(new { type = "chat_history_req", limit = 100 });
        }
    }
}
