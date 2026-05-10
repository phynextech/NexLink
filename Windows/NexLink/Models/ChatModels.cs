using System;
using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Runtime.CompilerServices;
using System.Windows.Media.Imaging;

namespace NexLink.Models
{
    // ── Transfer / Message State ─────────────────────────────────────────────
    public enum ChatMessageType
    {
        Text, Image, Video, Audio, Document, Apk, Archive, VoiceNote, Clipboard, Screenshot, File
    }

    public enum TransferState
    {
        None, Offered, Accepted, Transferring, Paused, Complete, Failed, Cancelled
    }

    // ── Chat Reaction ────────────────────────────────────────────────────────
    public class ChatReaction
    {
        public string Emoji   { get; set; } = "";
        public string SenderId{ get; set; } = "";
    }

    // ── Core chat message / file card ────────────────────────────────────────
    public class ChatMessage : INotifyPropertyChanged
    {
        private double          _transferProgress;
        private TransferState   _state = TransferState.None;
        private string          _transferSpeedLabel = "";
        private string          _etaLabel           = "";
        private bool            _isDelivered;
        private bool            _isRead;
        private bool            _isStarred;
        private string?         _reaction;
        private BitmapImage?    _thumbnail;

        public string          MessageId        { get; set; } = Guid.NewGuid().ToString();
        public string          SenderId         { get; set; } = "";   // "desktop" | "mobile"
        public bool            IsSentByMe       { get; set; }
        public ChatMessageType MessageType      { get; set; } = ChatMessageType.Text;

        // ── Text content ─────────────────────────────────────────────────────
        public string          Content          { get; set; } = "";

        // ── File metadata (no bytes stored) ──────────────────────────────────
        public string?         FileId           { get; set; }
        public string?         FileName         { get; set; }
        public string?         MimeType         { get; set; }
        public long            FileSizeBytes    { get; set; }
        public int             TotalChunks      { get; set; }
        public string?         LocalFilePath    { get; set; }   // populated after save

        // ── Reply threading ───────────────────────────────────────────────────
        public string?         ReplyToId        { get; set; }
        public string?         ReplyPreview     { get; set; }

        public DateTime        Timestamp        { get; set; } = DateTime.Now;
        public string          TimeLabel        => Timestamp.ToString("HH:mm");
        public string          FileSizeLabel    => FormatBytes(FileSizeBytes);

        // ── Observable properties ─────────────────────────────────────────────
        public double TransferProgress
        {
            get => _transferProgress;
            set { _transferProgress = value; OnPropertyChanged(); OnPropertyChanged(nameof(ProgressPercent)); }
        }
        public int ProgressPercent => (int)(_transferProgress * 100);

        public TransferState State
        {
            get => _state;
            set { _state = value; OnPropertyChanged(); }
        }

        public string TransferSpeedLabel
        {
            get => _transferSpeedLabel;
            set { _transferSpeedLabel = value; OnPropertyChanged(); }
        }
        public string EtaLabel
        {
            get => _etaLabel;
            set { _etaLabel = value; OnPropertyChanged(); }
        }

        public bool IsDelivered
        {
            get => _isDelivered;
            set { _isDelivered = value; OnPropertyChanged(); OnPropertyChanged(nameof(DeliveryIcon)); }
        }
        public bool IsRead
        {
            get => _isRead;
            set { _isRead = value; OnPropertyChanged(); OnPropertyChanged(nameof(DeliveryIcon)); }
        }
        public bool IsStarred
        {
            get => _isStarred;
            set { _isStarred = value; OnPropertyChanged(); }
        }
        public string? Reaction
        {
            get => _reaction;
            set { _reaction = value; OnPropertyChanged(); }
        }
        public BitmapImage? Thumbnail
        {
            get => _thumbnail;
            set { _thumbnail = value; OnPropertyChanged(); }
        }

        public string DeliveryIcon => IsRead ? "✓✓" : IsDelivered ? "✓" : "○";

        // ── Type helpers ──────────────────────────────────────────────────────
        public bool IsTextMessage   => MessageType == ChatMessageType.Text;
        public bool IsImageMessage  => MessageType == ChatMessageType.Image;
        public bool IsAudioMessage  => MessageType == ChatMessageType.Audio || MessageType == ChatMessageType.VoiceNote;
        public bool IsVideoMessage  => MessageType == ChatMessageType.Video;
        public bool IsFileMessage   => !IsTextMessage && !IsImageMessage && !IsAudioMessage && !IsVideoMessage;

        public string FileIcon => MessageType switch
        {
            ChatMessageType.Apk       => "📦",
            ChatMessageType.Archive   => "🗜",
            ChatMessageType.Document  => "📄",
            ChatMessageType.Audio     => "🎵",
            ChatMessageType.VoiceNote => "🎙",
            ChatMessageType.Video     => "🎬",
            ChatMessageType.Image     => "🖼",
            ChatMessageType.Clipboard => "📋",
            ChatMessageType.Screenshot=> "🖥",
            _                         => "📁"
        };

        // ── Formatting helpers ────────────────────────────────────────────────
        public static string FormatBytes(long bytes)
        {
            if (bytes <= 0)   return "";
            if (bytes < 1024) return $"{bytes} B";
            if (bytes < 1024 * 1024) return $"{bytes / 1024.0:F1} KB";
            if (bytes < 1024L * 1024 * 1024) return $"{bytes / (1024.0 * 1024):F1} MB";
            return $"{bytes / (1024.0 * 1024 * 1024):F2} GB";
        }

        public event PropertyChangedEventHandler? PropertyChanged;
        protected void OnPropertyChanged([CallerMemberName] string? n = null)
            => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(n));
    }

    // ── Saved file entry for the "Saved Files" section ───────────────────────
    public class SavedFileEntry : INotifyPropertyChanged
    {
        public string  FileId       { get; set; } = "";
        public string  FileName     { get; set; } = "";
        public string  MimeType     { get; set; } = "";
        public long    SizeBytes    { get; set; }
        public string  LocalPath    { get; set; } = "";
        public DateTime ReceivedAt  { get; set; } = DateTime.Now;
        public bool    IsStarred    { get; set; }
        public string  Direction    { get; set; } = "received"; // "sent" | "received"

        public string SizeLabel     => ChatMessage.FormatBytes(SizeBytes);
        public string TimeLabel     => ReceivedAt.ToString("MMM d, HH:mm");
        public string FileIcon      => MimeType switch
        {
            var m when m.StartsWith("image/") => "🖼",
            var m when m.StartsWith("video/") => "🎬",
            var m when m.StartsWith("audio/") => "🎵",
            "application/vnd.android.package-archive" => "📦",
            var m when m.Contains("zip") || m.Contains("rar") || m.Contains("7z") => "🗜",
            var m when m.Contains("pdf") => "📄",
            _ => "📁"
        };

        public event PropertyChangedEventHandler? PropertyChanged;
        protected void OnPropertyChanged([CallerMemberName] string? n = null)
            => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(n));
    }
}
