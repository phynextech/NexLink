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
    }

    public class NowPlayingMessage
    {
        public string Type => "now_playing";
        public string Title { get; set; } = "";
        public string Artist { get; set; } = "";
        public string? AlbumArtBase64 { get; set; }
        public bool IsPlaying { get; set; }
    }

    public class AppItem
    {
        public string Name { get; set; } = "";
        public string Path { get; set; } = "";
        public string? IconBase64 { get; set; }
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
    }

    public class FileListMessage
    {
        public string Type => "file_list";
        public List<FileItem> Files { get; set; } = new();
    }

    public class NotificationItem
    {
        public string App { get; set; } = "";
        public string Title { get; set; } = "";
        public string Body { get; set; } = "";
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
    }

    public enum ConnectionState
    {
        Disconnected,
        Connected
    }
}
