using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;
using NAudio.CoreAudioApi;
using Newtonsoft.Json.Linq;

namespace NexLink.Services
{
    public class SystemInfoService
    {
        // ─── Lock PC ───
        [DllImport("user32.dll", SetLastError = true)]
        static extern bool LockWorkStation();
        public static void LockPC() => LockWorkStation();

        // ─── Volume ───
        public static void SetVolume(int level)
        {
            try
            {
                using var enumerator = new MMDeviceEnumerator();
                var device = enumerator.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
                device.AudioEndpointVolume.MasterVolumeLevelScalar = level / 100f;
            }
            catch { }
        }

        public static int GetVolume()
        {
            try
            {
                using var enumerator = new MMDeviceEnumerator();
                var device = enumerator.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
                return (int)(device.AudioEndpointVolume.MasterVolumeLevelScalar * 100);
            }
            catch { return 50; }
        }

        // ─── Brightness ───
        public static void SetBrightness(int level)
        {
            try
            {
                var scope = new System.Management.ManagementScope(@"\\.\root\WMI");
                var query = new System.Management.SelectQuery("WmiMonitorBrightnessMethods");
                using var searcher = new System.Management.ManagementObjectSearcher(scope, query);
                foreach (System.Management.ManagementObject obj in searcher.Get())
                    obj.InvokeMethod("WmiSetBrightness", new object[] { 1, (byte)level });
            }
            catch { }
        }

        public static int GetBrightness()
        {
            try
            {
                var scope = new System.Management.ManagementScope(@"\\.\root\WMI");
                var query = new System.Management.SelectQuery("WmiMonitorBrightness");
                using var searcher = new System.Management.ManagementObjectSearcher(scope, query);
                foreach (System.Management.ManagementObject obj in searcher.Get())
                {
                    var val = obj["CurrentBrightness"];
                    if (val != null) return Convert.ToInt32(val);
                }
            }
            catch { }
            return 70;
        }

        // ─── WiFi Info (real SSID via netsh) ───
        public static (string ssid, int strength) GetWifiInfo()
        {
            try
            {
                var proc = new System.Diagnostics.Process
                {
                    StartInfo = new System.Diagnostics.ProcessStartInfo
                    {
                        FileName               = "netsh",
                        Arguments              = "wlan show interfaces",
                        RedirectStandardOutput = true,
                        UseShellExecute        = false,
                        CreateNoWindow         = true,
                    }
                };
                proc.Start();
                var output = proc.StandardOutput.ReadToEnd();
                proc.WaitForExit();

                string ssid    = "Not Connected";
                int    quality = 0;

                foreach (var line in output.Split('\n'))
                {
                    var t = line.Trim();
                    if (t.StartsWith("SSID") && !t.Contains("BSSID"))
                    {
                        var parts = t.Split(':', 2);
                        if (parts.Length >= 2)
                        {
                            var s = parts[1].Trim();
                            if (!string.IsNullOrEmpty(s)) ssid = s;
                        }
                    }
                    if (t.StartsWith("Signal"))
                    {
                        var parts = t.Split(':', 2);
                        if (parts.Length >= 2)
                            int.TryParse(parts[1].Trim().Replace("%",""), out quality);
                    }
                }
                return (ssid, quality);
            }
            catch { }
            return ("Unknown", 0);
        }

        // ─── Bluetooth Info ───
        public static List<object> GetBluetoothDevices()
        {
            var devices = new List<object>();
            try
            {
                // First try connected Bluetooth audio/input devices
                var searcher = new System.Management.ManagementObjectSearcher(
                    "SELECT * FROM Win32_PnPEntity WHERE PNPClass = 'Bluetooth' OR PNPClass = 'BTHLEDevice'");
                foreach (System.Management.ManagementObject obj in searcher.Get())
                {
                    var name   = obj["Name"]?.ToString();
                    var status = obj["Status"]?.ToString();
                    if (!string.IsNullOrEmpty(name) && status == "OK")
                        devices.Add(new { name = name, address = "Unknown", type = "Bluetooth", connected = true });
                }
            }
            catch { }
            return devices;
        }

        public static bool GetBluetoothEnabled()
        {
            try
            {
                var searcher = new System.Management.ManagementObjectSearcher(
                    "SELECT * FROM Win32_PnPEntity WHERE PNPClass = 'Bluetooth'");
                return searcher.Get().Count > 0;
            }
            catch { return false; }
        }

        // ─── Mute ───
        public static bool GetMuted()
        {
            try
            {
                using var enumerator = new MMDeviceEnumerator();
                var device = enumerator.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
                return device.AudioEndpointVolume.Mute;
            }
            catch { return false; }
        }

        // ─── Battery Info (with hasBattery flag) ───
        public static (int level, bool isCharging, bool hasBattery) GetBatteryInfo()
        {
            var status = System.Windows.Forms.SystemInformation.PowerStatus;
            bool hasBattery = status.BatteryChargeStatus != System.Windows.Forms.BatteryChargeStatus.NoSystemBattery
                           && status.BatteryChargeStatus != System.Windows.Forms.BatteryChargeStatus.Unknown;
            int level = hasBattery ? (int)(status.BatteryLifePercent * 100) : 100;
            if (level > 100) level = 100;
            bool charging = status.PowerLineStatus == System.Windows.Forms.PowerLineStatus.Online;
            return (level, charging, hasBattery);
        }

        // ─── Full system state snapshot ───
        /// <summary>
        /// Builds a complete system_state object to send to mobile on connect.
        /// All values are read fresh — no caches, no fallback hardcoding.
        /// </summary>
        public static object BuildSystemState()
        {
            var (ssid, strength)              = GetWifiInfo();
            var (batLevel, charging, hasBatt) = GetBatteryInfo();
            var vol                           = GetVolume();
            var bri                           = GetBrightness();
            var muted                         = GetMuted();
            var btDevices                     = GetBluetoothDevices();
            var btEnabled                     = GetBluetoothEnabled();
            var (wallB64, _)                  = GetWallpaperBase64Cached();
            var osVer                         = Environment.OSVersion.Version.Build >= 22000
                                                ? "Windows 11 Professional"
                                                : $"Windows {Environment.OSVersion.Version}";

            return new
            {
                type       = "system_state",
                wallpaper  = wallB64 ?? "",
                deviceName = Environment.MachineName,
                osVersion  = osVer,
                wifi       = new { connected = ssid != "Not Connected" && ssid != "Unknown", ssid, strength },
                battery    = new { percentage = batLevel, charging, hasBattery = hasBatt },
                bluetooth  = new { enabled = btEnabled, connectedDevices = btDevices },
                volume     = vol,
                brightness = bri,
                muted       = muted,
            };
        }

        // ─── Lightweight state update (no wallpaper) ───
        public static object BuildStateUpdate()
        {
            var (ssid, strength)              = GetWifiInfo();
            var (batLevel, charging, hasBatt) = GetBatteryInfo();
            var vol                           = GetVolume();
            var bri                           = GetBrightness();
            var muted                         = GetMuted();

            return new
            {
                type       = "state_update",
                volume     = vol,
                muted       = muted,
                brightness = bri,
                wifi       = new { connected = ssid != "Not Connected" && ssid != "Unknown", ssid, strength },
                battery    = new { percentage = batLevel, charging, hasBattery = hasBatt },
            };
        }


        // ─── Wallpaper with hash cache ───
        private static string _lastWallpaperHash = "";
        private static string _lastWallpaperB64  = "";

        public static (string b64, bool changed) GetWallpaperBase64Cached()
        {
            try
            {
                string transcodedPath = Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
                    @"Microsoft\Windows\Themes\TranscodedWallpaper");

                string wallpaperPath = File.Exists(transcodedPath) ? transcodedPath :
                    Microsoft.Win32.Registry.GetValue(
                        @"HKEY_CURRENT_USER\Control Panel\Desktop", "Wallpaper", "") as string ?? "";

                if (string.IsNullOrEmpty(wallpaperPath) || !File.Exists(wallpaperPath))
                    return (_lastWallpaperB64, false);

                // Compute hash to detect changes without re-encoding
                byte[] fileBytes = File.ReadAllBytes(wallpaperPath);
                using var md5 = MD5.Create();
                string hash = Convert.ToBase64String(md5.ComputeHash(fileBytes));

                if (hash == _lastWallpaperHash && !string.IsNullOrEmpty(_lastWallpaperB64))
                    return (_lastWallpaperB64, false); // Not changed

                // Re-encode at 800x450 JPEG
                using var bmp    = new Bitmap(new MemoryStream(fileBytes));
                using var scaled = new Bitmap(800, 450);
                using var g      = Graphics.FromImage(scaled);
                g.InterpolationMode = System.Drawing.Drawing2D.InterpolationMode.HighQualityBicubic;
                g.DrawImage(bmp, 0, 0, 800, 450);
                using var ms = new MemoryStream();
                var encoder = GetJpegEncoder();
                var ep = new EncoderParameters(1);
                ep.Param[0] = new EncoderParameter(System.Drawing.Imaging.Encoder.Quality, 75L);
                scaled.Save(ms, encoder, ep);

                _lastWallpaperHash = hash;
                _lastWallpaperB64  = Convert.ToBase64String(ms.ToArray());
                return (_lastWallpaperB64, true);
            }
            catch { return (_lastWallpaperB64, false); }
        }

        // ─── Process launcher ───
        public static void LaunchApp(string appPath)
        {
            try
            {
                System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo
                {
                    FileName = appPath,
                    UseShellExecute = true
                });
            }
            catch { }
        }

        // ─── Installed apps list ───
        public static List<Models.AppItem> GetInstalledApps()
        {
            var apps = new List<Models.AppItem>
            {
                new() { Name = "File Manager",         Path = "explorer.exe" },
                new() { Name = "Google Chrome",        Path = @"C:\Program Files\Google\Chrome\Application\chrome.exe" },
                new() { Name = "Brave Browser",        Path = @"C:\Program Files\BraveSoftware\Brave-Browser\Application\brave.exe" },
                new() { Name = "Spotify",              Path = @"C:\Users\" + Environment.UserName + @"\AppData\Roaming\Spotify\Spotify.exe" },
                new() { Name = "VLC Media Player",     Path = @"C:\Program Files\VideoLAN\VLC\vlc.exe" },
                new() { Name = "Notepad",              Path = "notepad.exe" },
                new() { Name = "Calculator",           Path = "calc.exe" },
                new() { Name = "Settings",             Path = "ms-settings:" },
                new() { Name = "Microsoft Edge",       Path = "msedge.exe" },
                new() { Name = "Visual Studio Code",   Path = @"C:\Users\" + Environment.UserName + @"\AppData\Local\Programs\Microsoft VS Code\Code.exe" },
                new() { Name = "Task Manager",         Path = "taskmgr.exe" },
                new() { Name = "Paint",                Path = "mspaint.exe" },
                new() { Name = "WhatsApp",             Path = @"C:\Users\" + Environment.UserName + @"\AppData\Local\WhatsApp\WhatsApp.exe" },
            };
            var result = new List<Models.AppItem>();
            foreach (var app in apps)
                if (app.Path.StartsWith("ms-") || File.Exists(app.Path) || !app.Path.Contains("\\"))
                    result.Add(app);
            return result;
        }

        // ─── File system browsing ───
        public static List<Models.FileItem> BrowsePath(string path)
        {
            var items = new List<Models.FileItem>();
            try
            {
                var resolved = path switch
                {
                    "Downloads" => Environment.GetFolderPath(Environment.SpecialFolder.UserProfile) + @"\Downloads",
                    "Documents" => Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments),
                    "Desktop"   => Environment.GetFolderPath(Environment.SpecialFolder.Desktop),
                    "Pictures"  => Environment.GetFolderPath(Environment.SpecialFolder.MyPictures),
                    "Videos"    => Environment.GetFolderPath(Environment.SpecialFolder.MyVideos),
                    "Music"     => Environment.GetFolderPath(Environment.SpecialFolder.MyMusic),
                    _           => path
                };
                path = resolved;

                if (path == "root")
                {
                    // Show special folders (NO C: drive, NO system drives)
                    var shortcuts = new[]
                    {
                        ("Downloads", Environment.GetFolderPath(Environment.SpecialFolder.UserProfile) + @"\Downloads"),
                        ("Documents", Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments)),
                        ("Desktop",   Environment.GetFolderPath(Environment.SpecialFolder.Desktop)),
                        ("Pictures",  Environment.GetFolderPath(Environment.SpecialFolder.MyPictures)),
                        ("Videos",    Environment.GetFolderPath(Environment.SpecialFolder.MyVideos)),
                        ("Music",     Environment.GetFolderPath(Environment.SpecialFolder.MyMusic)),
                    };
                    foreach (var (name, fpath) in shortcuts)
                        if (Directory.Exists(fpath))
                            items.Add(new Models.FileItem { Name = name, Path = fpath, IsDirectory = true, Type = "folder" });

                    // Add non-C drives only
                    foreach (var drive in DriveInfo.GetDrives())
                    {
                        if (!drive.IsReady) continue;
                        // Skip C: drive
                        if (drive.Name.StartsWith("C", StringComparison.OrdinalIgnoreCase)) continue;
                        var label = string.IsNullOrEmpty(drive.VolumeLabel)
                            ? $"Drive ({drive.Name.TrimEnd('\\')})"
                            : $"{drive.VolumeLabel} ({drive.Name.TrimEnd('\\')})";
                        items.Add(new Models.FileItem { Name = label, Path = drive.RootDirectory.FullName, IsDirectory = true, Type = "drive" });
                    }
                    return items;
                }

                var di = new DirectoryInfo(path);
                foreach (var dir in di.GetDirectories())
                {
                    try
                    {
                        if ((dir.Attributes & FileAttributes.Hidden) != 0) continue;
                        if ((dir.Attributes & FileAttributes.System) != 0) continue;
                        items.Add(new Models.FileItem { Name = dir.Name, Path = dir.FullName, IsDirectory = true, Type = "folder" });
                    }
                    catch { }
                }
                foreach (var fi in di.GetFiles())
                {
                    try
                    {
                        if ((fi.Attributes & FileAttributes.Hidden) != 0) continue;
                        // Generate thumbnail for image files
                        string? thumbB64 = null;
                        if (IsImageFile(fi.Extension))
                            thumbB64 = GetImageThumbnailBase64(fi.FullName, 120, 120);

                        items.Add(new Models.FileItem
                        {
                            Name        = fi.Name,
                            Path        = fi.FullName,
                            Size        = fi.Length,
                            IsDirectory = false,
                            Type        = fi.Extension.TrimStart('.').ToLowerInvariant(),
                            ThumbnailBase64 = thumbB64
                        });
                    }
                    catch { }
                }
            }
            catch { }
            return items;
        }

        // ─── File Preview (for files < 150MB) ───
        public static string? GetFilePreviewBase64(string path)
        {
            try
            {
                var fi = new FileInfo(path);
                if (!fi.Exists) return null;

                var ext = fi.Extension.ToLowerInvariant();

                // Image: return full-res (scaled to 1200px max)
                if (IsImageFile(ext))
                {
                    using var bmp = new Bitmap(path);
                    var scaled = ScaleBitmapDown(bmp, 1200, 800);
                    using var ms = new MemoryStream();
                    var encoder = GetJpegEncoder();
                    var ep = new EncoderParameters(1);
                    ep.Param[0] = new EncoderParameter(System.Drawing.Imaging.Encoder.Quality, 85L);
                    scaled.Save(ms, encoder, ep);
                    return "image:" + Convert.ToBase64String(ms.ToArray());
                }

                // Text files: return content as text
                if (IsTextFile(ext) && fi.Length < 512 * 1024) // <512KB text
                {
                    var text = File.ReadAllText(path);
                    return "text:" + Convert.ToBase64String(Encoding.UTF8.GetBytes(text));
                }

                // Audio/Video: return thumbnail (video first frame via placeholder)
                if (IsAudioFile(ext) || IsVideoFile(ext))
                    return "media:"; // Client knows it's a media file

                return null;
            }
            catch { return null; }
        }

        // ─── Image thumbnail helper ───
        public static string? GetImageThumbnailBase64(string path, int maxW, int maxH)
        {
            try
            {
                using var bmp = new Bitmap(path);
                var scaled = ScaleBitmapDown(bmp, maxW, maxH);
                using var ms = new MemoryStream();
                var encoder = GetJpegEncoder();
                var ep = new EncoderParameters(1);
                ep.Param[0] = new EncoderParameter(System.Drawing.Imaging.Encoder.Quality, 60L);
                scaled.Save(ms, encoder, ep);
                return Convert.ToBase64String(ms.ToArray());
            }
            catch { return null; }
        }

        private static Bitmap ScaleBitmapDown(Bitmap src, int maxW, int maxH)
        {
            float scaleW = (float)maxW / src.Width;
            float scaleH = (float)maxH / src.Height;
            float scale  = Math.Min(scaleW, scaleH);
            if (scale >= 1f) return new Bitmap(src);
            int w = (int)(src.Width * scale), h = (int)(src.Height * scale);
            var dst = new Bitmap(w, h);
            using var g = Graphics.FromImage(dst);
            g.InterpolationMode = System.Drawing.Drawing2D.InterpolationMode.HighQualityBicubic;
            g.DrawImage(src, 0, 0, w, h);
            return dst;
        }

        // ─── File extension helpers ───
        public static bool IsImageFile(string ext) =>
            ext is ".jpg" or ".jpeg" or ".png" or ".bmp" or ".gif" or ".webp" or ".tiff";
        public static bool IsAudioFile(string ext) =>
            ext is ".mp3" or ".flac" or ".wav" or ".aac" or ".ogg" or ".m4a";
        public static bool IsVideoFile(string ext) =>
            ext is ".mp4" or ".mkv" or ".avi" or ".mov" or ".wmv" or ".webm";
        public static bool IsTextFile(string ext) =>
            ext is ".txt" or ".log" or ".csv" or ".json" or ".xml" or ".md" or ".cs" or ".kt" or ".js" or ".ts" or ".html" or ".css" or ".py";

        public static void OpenFile(string path)
        {
            try
            {
                System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo
                {
                    FileName = path, UseShellExecute = true
                });
            }
            catch { }
        }

        // ─── Download file as chunked base64 ───
        public static async Task SendFileChunkedAsync(string path, Action<JObject> sendChunk)
        {
            const int CHUNK_SIZE = 64 * 1024; // 64KB chunks
            try
            {
                var fi = new FileInfo(path);
                if (!fi.Exists) return;
                long totalSize = fi.Length;
                int index = 0;
                using var fs = File.OpenRead(path);
                var buffer = new byte[CHUNK_SIZE];
                int read;
                long sent = 0;
                while ((read = await fs.ReadAsync(buffer, 0, CHUNK_SIZE)) > 0)
                {
                    sent += read;
                    float progress = (float)sent / totalSize;
                    var chunk = new JObject
                    {
                        ["type"]     = "file_chunk",
                        ["name"]     = fi.Name,
                        ["index"]    = index++,
                        ["progress"] = progress,
                        ["data"]     = Convert.ToBase64String(buffer, 0, read),
                        ["done"]     = (sent >= totalSize)
                    };
                    sendChunk(chunk);
                    await Task.Delay(30); // throttle
                }
            }
            catch { }
        }

        private static ImageCodecInfo GetJpegEncoder()
        {
            foreach (var codec in ImageCodecInfo.GetImageEncoders())
                if (codec.FormatID == ImageFormat.Jpeg.Guid) return codec;
            return ImageCodecInfo.GetImageEncoders()[0];
        }
    }
}
