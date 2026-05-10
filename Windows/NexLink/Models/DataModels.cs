using System;
using System.Collections.Generic;

namespace NexLink.Models
{
    public class DeviceQRPayload
    {
        public string Ip { get; set; } = "";
        public int Port { get; set; } = 8765;
        public string DeviceName { get; set; } = "";
        public string SessionToken { get; set; } = "";
    }

    public class WsMessage
    {
        public string Type { get; set; } = "";
    }

    public class WifiInfoMessage
    {
        public string Type => "wifi_info";
        public string Ssid { get; set; } = "";
        public int Strength { get; set; }
    }

    public class BatteryInfoMessage
    {
        public string Type => "battery_info";
        public int Level { get; set; }
        public bool IsCharging { get; set; }
    }

    public class BluetoothDevice
    {
        public string Name { get; set; } = "";
        public string Address { get; set; } = "";
        public string Type { get; set; } = "";
    }

    public class BtInfoMessage
    {
        public string Type => "bt_info";
        public List<BluetoothDevice> Devices { get; set; } = new();
        public bool BluetoothEnabled { get; set; }
    }

    public class NowPlayingMessage
    {
        public string Type => "now_playing";
        public string Title { get; set; } = "";
        public string Artist { get; set; } = "";
        public string? AlbumArtBase64 { get; set; }
        public bool IsPlaying { get; set; }
        public double Position { get; set; }
        public double Duration { get; set; }
        public string AppSource { get; set; } = ""; // e.g. "Spotify", "YouTube (Brave)"
        public bool ShuffleActive { get; set; }
        public int RepeatMode { get; set; } // 0=Off, 1=All, 2=One
    }

    public class AppItem
    {
        public string Name { get; set; } = "";
        public string Path { get; set; } = "";
        public string? IconBase64 { get; set; }
        public string Category { get; set; } = "";
        public string Handle { get; set; } = "";
        public bool IsForeground { get; set; }
    }

    public class AppListMessage
    {
        public string Type => "app_list";
        public List<AppItem> Apps { get; set; } = new();
    }

    public class FileItem
    {
        public string Name { get; set; } = "";
        public string Path { get; set; } = "";
        public long Size { get; set; }
        public bool IsDirectory { get; set; }
        public string Type { get; set; } = "file";
        public string? ThumbnailBase64 { get; set; } // For image files
        public long LastModified { get; set; }
    }

    public class FileListMessage
    {
        public string Type => "file_list";
        public List<FileItem> Files { get; set; } = new();
    }

    public class NotificationItem
    {
        public string AppName { get; set; } = "";
        public string Title { get; set; } = "";
        public string Body { get; set; } = "";
        public string Key { get; set; } = "";
        public DateTime Timestamp { get; set; } = DateTime.Now;
    }

    public class SmsMessage
    {
        public string Id { get; set; } = "";
        public string Body { get; set; } = "";
        public DateTime Timestamp { get; set; }
        public bool IsSent { get; set; }
    }

    public class SmsThread
    {
        public string Id { get; set; } = "";
        public string ContactName { get; set; } = "";
        public string ContactNumber { get; set; } = "";
        public string LastMessage { get; set; } = "";
        public DateTime Timestamp { get; set; }
        public int Unread { get; set; }
        public List<SmsMessage> Messages { get; set; } = new();
    }

    public class PhotoItem
    {
        public string Name { get; set; } = "";
        public string Path { get; set; } = "";
        public string? ThumbnailBase64 { get; set; }
        public DateTime Timestamp { get; set; }
        public string Album { get; set; } = "Camera";   // "Camera", "WhatsApp Images", "Screenshots", "Download"
    }

    /// <summary>Album of photos from the Android device, grouped by folder.</summary>
    public class PhotoAlbum
    {
        public string Name { get; set; } = "";           // e.g. "Camera"
        public string? CoverThumbnail { get; set; }     // base64 first photo (optional)
        public int PhotoCount { get; set; }
        public List<PhotoItem> Photos { get; set; } = new();
    }

    /// <summary>Live Android device status: ringer mode, volume, notification count.</summary>
    public class MobileStatus
    {
        /// <summary>0=Silent, 1=Vibrate, 2=Normal</summary>
        public int RingerMode { get; set; } = 2;
        public int PhoneVolume { get; set; }    // 0-100 media stream %
        public int RingerVolume { get; set; }   // 0-100 ringer stream %
        public int NotifCount { get; set; }
        public bool IsDoNotDisturb { get; set; }
    }

    public enum ConnectionState
    {
        Disconnected,
        Connected
    }
}
