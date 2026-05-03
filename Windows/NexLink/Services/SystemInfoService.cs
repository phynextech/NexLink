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
                level = Math.Clamp(level, 0, 100);
                using var enumerator = new MMDeviceEnumerator();
                var device = enumerator.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
                float scalar = level / 100f;
                device.AudioEndpointVolume.MasterVolumeLevelScalar = scalar;
                float verify = device.AudioEndpointVolume.MasterVolumeLevelScalar;
                Console.WriteLine($"[SetVolume] Set to {level}% → verified {(int)(verify * 100)}%");
            }
            catch (Exception ex)
            {
                Console.WriteLine($"[SetVolume] FAILED: {ex.Message}");
            }
        }

        public static int GetVolume()
        {
            try
            {
                using var enumerator = new MMDeviceEnumerator();
                var device = enumerator.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
                float scalar = device.AudioEndpointVolume.MasterVolumeLevelScalar;
                int vol = (int)Math.Round(scalar * 100f);
                Console.WriteLine($"[GetVolume] scalar={scalar:F4} → {vol}%");
                return vol;
            }
            catch (Exception ex)
            {
                Console.WriteLine($"[GetVolume] FAILED: {ex.Message}");
                return -1; // -1 = failed, not a default
            }
        }

        // ─── Brightness ───
        public static void SetBrightness(int level)
        {
            try
            {
                level = Math.Clamp(level, 0, 100);
                using var searcher = new System.Management.ManagementObjectSearcher(
                    "root\\WMI", "SELECT * FROM WmiMonitorBrightnessMethods");
                bool found = false;
                foreach (System.Management.ManagementObject obj in searcher.Get())
                {
                    obj.InvokeMethod("WmiSetBrightness", new object[] { (uint)1, (byte)level });
                    Console.WriteLine($"[SetBrightness] Set to {level}%");
                    found = true;
                }
                if (!found)
                    Console.WriteLine("[SetBrightness] No WMI methods found (desktop monitor?)");
            }
            catch (Exception ex)
            {
                Console.WriteLine($"[SetBrightness] FAILED: {ex.Message}");
            }
        }

        public static int GetBrightness()
        {
            try
            {
                using var searcher = new System.Management.ManagementObjectSearcher(
                    "root\\WMI", "SELECT CurrentBrightness FROM WmiMonitorBrightness");
                foreach (System.Management.ManagementObject obj in searcher.Get())
                {
                    int brightness = Convert.ToInt32(obj["CurrentBrightness"]);
                    Console.WriteLine($"[GetBrightness] WMI returned {brightness}%");
                    return brightness;
                }
                Console.WriteLine("[GetBrightness] WMI returned no results (desktop monitor?)");
                return -1;
            }
            catch (Exception ex)
            {
                Console.WriteLine($"[GetBrightness] FAILED: {ex.Message}");
                return -1;
            }
        }

        // ─── WiFi Info (real SSID via netsh) ───
        public static (string ssid, int strength) GetWifiInfo()
        {
            try
            {
                var psi = new System.Diagnostics.ProcessStartInfo
                {
                    FileName               = "netsh",
                    Arguments              = "wlan show interfaces",
                    RedirectStandardOutput = true,
                    UseShellExecute        = false,
                    CreateNoWindow         = true,
                    StandardOutputEncoding = System.Text.Encoding.UTF8,
                };
                using var proc = System.Diagnostics.Process.Start(psi)!;
                string output = proc.StandardOutput.ReadToEnd();
                proc.WaitForExit();

                Console.WriteLine($"[GetWifiInfo] netsh output length: {output.Length}");

                // Multiline regex: match SSID line that is NOT BSSID
                var ssidMatch = System.Text.RegularExpressions.Regex.Match(
                    output, @"^\s+SSID\s*:\s*(.+)$",
                    System.Text.RegularExpressions.RegexOptions.Multiline);

                var sigMatch = System.Text.RegularExpressions.Regex.Match(
                    output, @"Signal\s*:\s*(\d+)%",
                    System.Text.RegularExpressions.RegexOptions.Multiline);

                if (ssidMatch.Success)
                {
                    string ssid = ssidMatch.Groups[1].Value.Trim();
                    int.TryParse(sigMatch.Groups[1].Value.Trim(), out int quality);
                    Console.WriteLine($"[GetWifiInfo] SSID='{ssid}' Signal={quality}%");
                    return (ssid, quality);
                }

                if (output.Contains("There is no wireless interface"))
                {
                    Console.WriteLine("[GetWifiInfo] No wireless interface");
                    return ("No WiFi adapter", 0);
                }

                Console.WriteLine("[GetWifiInfo] Not connected to any network");
                return ("Not connected", 0);
            }
            catch (Exception ex)
            {
                Console.WriteLine($"[GetWifiInfo] FAILED: {ex.Message}");
                return ("Error", 0);
            }
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
            try
            {
                // WMI Win32_Battery is more reliable than SystemInformation on laptops
                using var searcher = new System.Management.ManagementObjectSearcher(
                    "SELECT EstimatedChargeRemaining, BatteryStatus FROM Win32_Battery");
                var results = searcher.Get();
                if (results.Count == 0)
                {
                    Console.WriteLine("[GetBatteryInfo] No Win32_Battery (desktop PC)");
                    // Fallback to SystemInformation for desktops
                    var ps = System.Windows.Forms.SystemInformation.PowerStatus;
                    bool onAc = ps.PowerLineStatus == System.Windows.Forms.PowerLineStatus.Online;
                    return (100, onAc, false);
                }
                foreach (System.Management.ManagementObject bat in results)
                {
                    int pct    = Convert.ToInt32(bat["EstimatedChargeRemaining"]);
                    int status = Convert.ToInt32(bat["BatteryStatus"]);
                    // BatteryStatus 2 = Fully Charged/AC, 6 = Charging
                    bool charging = status == 2 || status == 6;
                    Console.WriteLine($"[GetBatteryInfo] {pct}% charging={charging} status={status}");
                    return (pct, charging, true);
                }
            }
            catch (Exception ex)
            {
                Console.WriteLine($"[GetBatteryInfo] WMI failed: {ex.Message} — falling back to SystemInformation");
                try
                {
                    var ps = System.Windows.Forms.SystemInformation.PowerStatus;
                    bool hasBattery = ps.BatteryChargeStatus != System.Windows.Forms.BatteryChargeStatus.NoSystemBattery
                                   && ps.BatteryChargeStatus != System.Windows.Forms.BatteryChargeStatus.Unknown;
                    int level = hasBattery ? (int)(ps.BatteryLifePercent * 100) : 100;
                    if (level > 100) level = 100;
                    bool charging = ps.PowerLineStatus == System.Windows.Forms.PowerLineStatus.Online;
                    Console.WriteLine($"[GetBatteryInfo] Fallback: {level}% charging={charging} hasBat={hasBattery}");
                    return (level, charging, hasBattery);
                }
                catch { }
            }
            return (0, false, false);
        }

        // ─── Full system state snapshot ───
        /// <summary>
        /// Builds a complete system_state with a pre-fetched wallpaper (used on connect).
        /// </summary>
        public static object BuildSystemState(string? wallpaperB64 = null)
        {
            var (ssid, strength)              = GetWifiInfo();
            var (batLevel, charging, hasBatt) = GetBatteryInfo();
            var vol                           = GetVolume();
            var bri                           = GetBrightness();
            var muted                         = GetMuted();
            var btDevices                     = GetBluetoothDevices();
            var btEnabled                     = GetBluetoothEnabled();
            if (wallpaperB64 == null)
            {
                var (wb, _) = GetWallpaperBase64Cached();
                wallpaperB64 = wb ?? "";
            }
            var osVer = Environment.OSVersion.Version.Build >= 22000
                        ? "Windows 11 Professional"
                        : $"Windows {Environment.OSVersion.Version}";

            // wifi connected = has an SSID (not error/unknown/not-connected strings)
            bool wifiOk = !string.IsNullOrEmpty(ssid)
                       && ssid != "Not connected"
                       && ssid != "Not Connected"
                       && ssid != "Unknown"
                       && ssid != "No WiFi adapter"
                       && ssid != "Error";

            return new
            {
                type       = "system_state",
                wallpaper  = wallpaperB64,
                deviceName = Environment.MachineName,
                osVersion  = osVer,
                wifi       = new { connected = wifiOk, ssid, strength },
                battery    = new { percentage = batLevel, charging, hasBattery = hasBatt },
                bluetooth  = new { enabled = btEnabled, connectedDevices = btDevices },
                volume     = vol,
                brightness = bri,
                muted,
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

            bool wifiOk = !string.IsNullOrEmpty(ssid)
                       && ssid != "Not connected"
                       && ssid != "Not Connected"
                       && ssid != "Unknown"
                       && ssid != "No WiFi adapter"
                       && ssid != "Error";

            return new
            {
                type       = "state_update",
                volume     = vol,
                muted,
                brightness = bri,
                wifi       = new { connected = wifiOk, ssid, strength },
                battery    = new { percentage = batLevel, charging, hasBattery = hasBatt },
            };
        }


        // ─── Wallpaper with hash cache ───
        private static string _lastWallpaperHash = "";
        private static string _lastWallpaperB64  = "";

        public static (string b64, bool changed) GetWallpaperBase64Cached()
            => GetWallpaperBase64(forceRefresh: false);

        /// <summary>
        /// Reads and encodes the wallpaper.
        /// forceRefresh=true bypasses the hash cache — always returns the current wallpaper.
        /// </summary>
        public static (string b64, bool changed) GetWallpaperBase64(bool forceRefresh = false)
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
                {
                    Console.WriteLine("[GetWallpaper] No wallpaper file found");
                    return (_lastWallpaperB64, false);
                }

                // Compute hash to detect changes without re-encoding
                byte[] fileBytes = File.ReadAllBytes(wallpaperPath);
                using var md5 = MD5.Create();
                string hash = Convert.ToBase64String(md5.ComputeHash(fileBytes));

                if (!forceRefresh && hash == _lastWallpaperHash && !string.IsNullOrEmpty(_lastWallpaperB64))
                    return (_lastWallpaperB64, false); // Not changed

                // Re-encode at 800x450 JPEG
                Console.WriteLine($"[GetWallpaper] Encoding wallpaper from: {wallpaperPath}");
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
                Console.WriteLine($"[GetWallpaper] Encoded {_lastWallpaperB64.Length} chars");
                return (_lastWallpaperB64, true);
            }
            catch (Exception ex)
            {
                Console.WriteLine($"[GetWallpaper] FAILED: {ex.Message}");
                return (_lastWallpaperB64, false);
            }
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
