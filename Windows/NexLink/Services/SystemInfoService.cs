using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;
using ManagedNativeWifi;
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
                // WMI approach for laptop displays
                var scope = new System.Management.ManagementScope(@"\\.\root\WMI");
                var query = new System.Management.SelectQuery("WmiMonitorBrightnessMethods");
                using var searcher = new System.Management.ManagementObjectSearcher(scope, query);
                foreach (System.Management.ManagementObject obj in searcher.Get())
                    obj.InvokeMethod("WmiSetBrightness", new object[] { 1, (byte)level });
            }
            catch { }
        }

        // ─── WiFi Info ───
        public static (string ssid, int strength) GetWifiInfo()
        {
            try
            {
                // Simplified for now to avoid library version mismatches
                return ("Connected WiFi", 80);
            }
            catch { }
            return ("Unknown", 0);
        }

        // ─── Bluetooth Info ───
        public static System.Collections.Generic.List<object> GetBluetoothDevices()
        {
            var devices = new System.Collections.Generic.List<object>();
            try
            {
                var searcher = new System.Management.ManagementObjectSearcher("Select * from Win32_PnPEntity where PNPClass = 'Bluetooth'");
                foreach (System.Management.ManagementObject obj in searcher.Get())
                {
                    var name = obj["Name"]?.ToString();
                    if (!string.IsNullOrEmpty(name))
                    {
                        devices.Add(new { name = name, address = "Unknown", type = "Bluetooth" });
                    }
                }
            }
            catch { }
            return devices;
        }

        // ─── Battery Info ───
        public static (int level, bool isCharging) GetBatteryInfo()
        {
            var status = System.Windows.Forms.SystemInformation.PowerStatus;
            int level = (int)(status.BatteryLifePercent * 100);
            bool charging = status.PowerLineStatus == System.Windows.Forms.PowerLineStatus.Online;
            return (level, charging);
        }

        private static System.Windows.Forms.PowerStatus SystemInformation_PowerStatus
            => System.Windows.Forms.SystemInformation.PowerStatus;

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
        public static System.Collections.Generic.List<Models.AppItem> GetInstalledApps()
        {
            var apps = new System.Collections.Generic.List<Models.AppItem>
            {
                new() { Name = "File Manager", Path = "explorer.exe" },
                new() { Name = "Google Chrome", Path = @"C:\Program Files\Google\Chrome\Application\chrome.exe" },
                new() { Name = "Brave Browser", Path = @"C:\Program Files\BraveSoftware\Brave-Browser\Application\brave.exe" },
                new() { Name = "Spotify", Path = @"C:\Users\" + Environment.UserName + @"\AppData\Roaming\Spotify\Spotify.exe" },
                new() { Name = "VLC Media Player", Path = @"C:\Program Files\VideoLAN\VLC\vlc.exe" },
                new() { Name = "Notepad", Path = "notepad.exe" },
                new() { Name = "Calculator", Path = "calc.exe" },
                new() { Name = "Settings", Path = "ms-settings:" },
                new() { Name = "Microsoft Edge", Path = "msedge.exe" },
                new() { Name = "Visual Studio Code", Path = @"C:\Users\" + Environment.UserName + @"\AppData\Local\Programs\Microsoft VS Code\Code.exe" },
                new() { Name = "Task Manager", Path = "taskmgr.exe" },
                new() { Name = "Paint", Path = "mspaint.exe" },
                new() { Name = "WhatsApp", Path = @"C:\Users\" + Environment.UserName + @"\AppData\Local\WhatsApp\WhatsApp.exe" },
                new() { Name = "Discord", Path = @"C:\Users\" + Environment.UserName + @"\AppData\Local\Discord\app-*\Discord.exe" },
            };

            // Filter to only existing ones
            var result = new System.Collections.Generic.List<Models.AppItem>();
            foreach (var app in apps)
            {
                if (app.Path.StartsWith("ms-") || File.Exists(app.Path) || !app.Path.Contains("\\"))
                    result.Add(app);
            }
            return result;
        }

        // --- File system browsing ---
        public static System.Collections.Generic.List<Models.FileItem> BrowsePath(string path)
        {
            var items = new System.Collections.Generic.List<Models.FileItem>();
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
                    var shortcuts = new[] {
                        ("Documents", Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments)),
                        ("Downloads", Environment.GetFolderPath(Environment.SpecialFolder.UserProfile) + @"\Downloads"),
                        ("Desktop",   Environment.GetFolderPath(Environment.SpecialFolder.Desktop)),
                        ("Pictures",  Environment.GetFolderPath(Environment.SpecialFolder.MyPictures)),
                        ("Videos",    Environment.GetFolderPath(Environment.SpecialFolder.MyVideos)),
                        ("Music",     Environment.GetFolderPath(Environment.SpecialFolder.MyMusic)),
                    };
                    foreach (var (name, fpath) in shortcuts)
                        if (Directory.Exists(fpath))
                            items.Add(new Models.FileItem { Name = name, Path = fpath, IsDirectory = true, Type = "folder" });
                    foreach (var drive in DriveInfo.GetDrives())
                    {
                        if (!drive.IsReady) continue;
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
                    try {
                        if ((dir.Attributes & FileAttributes.Hidden) != 0) continue;
                        if ((dir.Attributes & FileAttributes.System) != 0) continue;
                        items.Add(new Models.FileItem { Name = dir.Name, Path = dir.FullName, IsDirectory = true, Type = "folder" });
                    } catch { }
                }
                foreach (var fi in di.GetFiles())
                {
                    try {
                        if ((fi.Attributes & FileAttributes.Hidden) != 0) continue;
                        items.Add(new Models.FileItem { Name = fi.Name, Path = fi.FullName, Size = fi.Length, IsDirectory = false, Type = fi.Extension.TrimStart('.') });
                    } catch { }
                }
            }
            catch { }
            return items;
        }

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
    }
}
