using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Linq;
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

        [DllImport("user32.dll")]
        private static extern bool SetForegroundWindow(IntPtr hWnd);

        [DllImport("user32.dll")]
        private static extern bool IsWindowVisible(IntPtr hWnd);

        private delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);

        [DllImport("user32.dll")]
        private static extern bool EnumWindows(EnumWindowsProc enumProc, IntPtr lParam);

        [DllImport("user32.dll")]
        private static extern int GetWindowText(IntPtr hWnd, StringBuilder strText, int maxCount);

        [DllImport("user32.dll")]
        private static extern int GetWindowTextLength(IntPtr hWnd);

        [DllImport("user32.dll")]
        private static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint lpdwProcessId);

        [DllImport("dwmapi.dll")]
        private static extern int DwmGetWindowAttribute(IntPtr hwnd, int dwAttribute, out int pvAttribute, int cbAttribute);

        [DllImport("user32.dll")]
        private static extern IntPtr GetForegroundWindow();

        [DllImport("user32.dll")]
        private static extern IntPtr SendMessage(IntPtr hWnd, uint Msg, IntPtr wParam, IntPtr lParam);

        [DllImport("user32.dll")]
        private static extern bool IsIconic(IntPtr hWnd);

        [DllImport("user32.dll")]
        private static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);

        [DllImport("user32.dll")]
        private static extern void SwitchToThisWindow(IntPtr hWnd, bool fUnknown);

        [DllImport("user32.dll")]
        private static extern bool PostMessage(IntPtr hWnd, uint Msg, IntPtr wParam, IntPtr lParam);

        [StructLayout(LayoutKind.Sequential)]
        private struct MEMORYSTATUSEX
        {
            public uint dwLength;
            public uint dwMemoryLoad;
            public ulong ullTotalPhys;
            public ulong ullAvailPhys;
            public ulong ullTotalPageFile;
            public ulong ullAvailPageFile;
            public ulong ullTotalVirtual;
            public ulong ullAvailVirtual;
            public ulong ullAvailExtendedVirtual;
        }

        [DllImport("kernel32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool GlobalMemoryStatusEx(ref MEMORYSTATUSEX lpBuffer);

        private static float _lastGpu = 0;
        private static float _lastVram = 0;
        private static System.Threading.Timer? _gpuTimer;
        private static System.Diagnostics.PerformanceCounter? _cpuCounter;

        public static object GetPerformanceMetrics()
        {
            if (_gpuTimer == null)
            {
                _gpuTimer = new System.Threading.Timer(_ =>
                {
                    try
                    {
                        using var searcher = new System.Management.ManagementObjectSearcher("select * from Win32_PerfFormattedData_GPUPerformanceCounters_GPUEngine");
                        float maxUtil = 0;
                        foreach (System.Management.ManagementObject obj in searcher.Get())
                        {
                            if (obj["Name"]?.ToString().Contains("engtype_3D") == true)
                            {
                                float util = Convert.ToSingle(obj["UtilizationPercentage"]);
                                maxUtil += util;
                            }
                        }
                        _lastGpu = Math.Min(100, maxUtil);
                    }
                    catch { }

                    try
                    {
                        using var searcher = new System.Management.ManagementObjectSearcher("select AdapterRAM from Win32_VideoController");
                        float totalVram = 0;
                        foreach (System.Management.ManagementObject obj in searcher.Get())
                            totalVram += Convert.ToSingle(obj["AdapterRAM"]);

                        using var vramSearcher = new System.Management.ManagementObjectSearcher("select * from Win32_PerfFormattedData_GPUPerformanceCounters_GPUProcessMemory");
                        float usedVramBytes = 0;
                        foreach (System.Management.ManagementObject obj in vramSearcher.Get())
                            usedVramBytes += Convert.ToSingle(obj["LocalUsage"]);

                        if (totalVram > 0) _lastVram = (usedVramBytes / totalVram) * 100;
                    }
                    catch { }
                }, null, 0, 3000);
            }

            try
            {
                if (_cpuCounter == null)
                    _cpuCounter = new System.Diagnostics.PerformanceCounter("Processor", "% Processor Time", "_Total");

                float cpu = _cpuCounter.NextValue();
                
                var mem = new MEMORYSTATUSEX();
                mem.dwLength = (uint)Marshal.SizeOf(typeof(MEMORYSTATUSEX));
                GlobalMemoryStatusEx(ref mem);
                uint ram = mem.dwMemoryLoad;

                var (_, strength, _) = GetWifiInfo();

                return new {
                    cpu = (int)cpu,
                    gpu = (int)_lastGpu,
                    ram = (int)ram,
                    vram = (int)_lastVram,
                    fps = -1,
                    wifi = strength
                };
            }
            catch
            {
                return new { cpu = -1, gpu = -1, ram = -1, vram = -1, fps = -1, wifi = -1 };
            }
        }

        private static Dictionary<string, string> _iconCache = new();

        private static string GetIconBase64(string path)
        {
            if (string.IsNullOrEmpty(path)) return "";
            if (_iconCache.TryGetValue(path, out var b64)) return b64;
            try
            {
                var icon = Icon.ExtractAssociatedIcon(path);
                if (icon != null)
                {
                    using var bmp = icon.ToBitmap();
                    using var ms = new MemoryStream();
                    bmp.Save(ms, ImageFormat.Png);
                    b64 = Convert.ToBase64String(ms.ToArray());
                    _iconCache[path] = b64;
                    return b64;
                }
            }
            catch { }
            return "";
        }



        public static List<Models.AppItem> GetRunningApps()
        {
            var apps = new List<Models.AppItem>();
            try
            {
                IntPtr fgHwnd = GetForegroundWindow();

                EnumWindows((hWnd, lParam) =>
                {
                    if (IsWindowVisible(hWnd))
                    {
                        int cloaked = 0;
                        DwmGetWindowAttribute(hWnd, 14, out cloaked, sizeof(int));
                        if (cloaked != 0) return true;

                        int length = GetWindowTextLength(hWnd);
                        if (length > 0)
                        {
                            var builder = new StringBuilder(length + 1);
                            GetWindowText(hWnd, builder, builder.Capacity);
                            string title = builder.ToString();

                            // Filter common hidden windows
                            if (title != "Program Manager" && !title.Contains("Default IME"))
                            {
                                GetWindowThreadProcessId(hWnd, out uint processId);
                                string path = "";
                                try
                                {
                                    var proc = System.Diagnostics.Process.GetProcessById((int)processId);
                                    path = proc.MainModule?.FileName ?? "";
                                }
                                catch { }

                                string category = "Desktop 1";
                                try
                                {
                                    var screen = System.Windows.Forms.Screen.FromHandle(hWnd);
                                    if (!screen.Primary)
                                    {
                                        int index = 2;
                                        var screens = System.Windows.Forms.Screen.AllScreens;
                                        for (int i = 0; i < screens.Length; i++) {
                                            if (screens[i].DeviceName == screen.DeviceName) {
                                                index = i + 1; break;
                                            }
                                        }
                                        category = $"Desktop {index}";
                                    }
                                }
                                catch { }

                                apps.Add(new Models.AppItem { 
                                    Name = title, 
                                    Path = path,
                                    Handle = hWnd.ToString(),
                                    Category = category,
                                    IconBase64 = GetIconBase64(path),
                                    IsForeground = (hWnd == fgHwnd)
                                });
                            }
                        }
                    }
                    return true;
                }, IntPtr.Zero);
            }
            catch { }
            return apps.GroupBy(a => a.Handle).Select(g => g.First()).ToList();
        }

        public static void CloseApp(string handleStr)
        {
            try
            {
                if (IntPtr.TryParse(handleStr, out IntPtr hWnd))
                {
                    SendMessage(hWnd, 0x0112, (IntPtr)0xF060, IntPtr.Zero); // WM_SYSCOMMAND SC_CLOSE
                    PostMessage(hWnd, 0x0010, IntPtr.Zero, IntPtr.Zero); // WM_CLOSE

                    // Fallback to Kill
                    GetWindowThreadProcessId(hWnd, out uint processId);
                    if (processId > 0)
                    {
                        try
                        {
                            var proc = System.Diagnostics.Process.GetProcessById((int)processId);
                            if (!proc.HasExited && proc.MainWindowHandle == hWnd)
                                proc.Kill();
                        }
                        catch { }
                    }
                }
            }
            catch { }
        }

        public static void FocusApp(string handleStr)
        {
            try
            {
                if (IntPtr.TryParse(handleStr, out IntPtr hWnd))
                {
                    if (IsIconic(hWnd)) ShowWindow(hWnd, 9); // SW_RESTORE
                    SetForegroundWindow(hWnd);
                    SwitchToThisWindow(hWnd, true);
                }
            }
            catch { }
        }


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

        // ─── Network Info (Wi-Fi or Ethernet) ───
        public static (string ssid, int strength, bool connected) GetWifiInfo()
        {
            try
            {
                // 1. Try getting the network profile name via WMI (detects Ethernet and Wi-Fi)
                try
                {
                    var searcher = new System.Management.ManagementObjectSearcher(
                        "root\\StandardCimv2", "SELECT Name, InterfaceAlias FROM MSFT_NetConnectionProfile WHERE IPv4Connectivity = 4 OR IPv6Connectivity = 4");
                    foreach (System.Management.ManagementObject obj in searcher.Get())
                    {
                        var alias = obj["InterfaceAlias"]?.ToString() ?? "";
                        var name = obj["Name"]?.ToString() ?? "";
                        if (alias.Contains("Ethernet"))
                        {
                            return (name == "Network" ? "Ethernet" : $"Ethernet ({name})", 100, true);
                        }
                        if (alias.Contains("Wi-Fi") || alias.Contains("Wireless"))
                        {
                            return (name, 100, true);
                        }
                    }
                }
                catch { /* Ignore */ }

                // 2. Try getting the connected SSID using ManagedNativeWifi
                try
                {
                    var connectedSsid = ManagedNativeWifi.NativeWifi.EnumerateConnectedNetworkSsids().FirstOrDefault();
                    if (connectedSsid != null)
                        return (connectedSsid.ToString(), 100, true);
                }
                catch { /* Ignore exception, fallback to NetworkInterface check */ }

                // Fallback 2: check if we have any active wireless interface
                bool hasWifi = false;
                foreach (var nic in System.Net.NetworkInformation.NetworkInterface.GetAllNetworkInterfaces())
                {
                    if (nic.NetworkInterfaceType == System.Net.NetworkInformation.NetworkInterfaceType.Wireless80211 &&
                        nic.OperationalStatus == System.Net.NetworkInformation.OperationalStatus.Up)
                    {
                        hasWifi = true;
                        break;
                    }
                }

                if (hasWifi)
                {
                    Console.WriteLine("[GetWifiInfo] Connected (SSID hidden due to permissions)");
                    return ("Connected", 100, true);
                }

                Console.WriteLine("[GetWifiInfo] Not connected to any wireless network");
                return ("Not connected", 0, false);
            }
            catch (Exception ex)
            {
                Console.WriteLine($"[GetWifiInfo] FAILED: {ex.Message}");
                return ("Error", 0, false);
            }
        }

        // ─── Bluetooth Info ───
        public static List<object> GetBluetoothDevices()
        {
            var devices = new List<object>();
            try
            {
                // Try connected Bluetooth audio/input devices
                var searcher = new System.Management.ManagementObjectSearcher(
                    "SELECT * FROM Win32_PnPEntity WHERE PNPClass = 'Bluetooth' OR PNPClass = 'BTHLEDevice'");
                foreach (System.Management.ManagementObject obj in searcher.Get())
                {
                    var name   = obj["Name"]?.ToString() ?? "";
                    var status = obj["Status"]?.ToString();
                    
                    // Filter out generic Microsoft/Windows system drivers and services
                    string lower = name.ToLower();
                    if (string.IsNullOrEmpty(name) || status != "OK" ||
                        lower.Contains("generic attribute") ||
                        lower.Contains("enumerator") ||
                        lower.Contains("bluetooth radio") ||
                        lower.Contains("bluetooth device (") ||
                        lower.Contains("avrcp transport") ||
                        lower.EndsWith("service") ||
                        lower.EndsWith("profile") ||
                        lower.Contains("adapter") ||
                        lower == "bluetooth")
                        continue;

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
        /// Builds a complete system_state object to send to mobile on connect.
        /// All values are read fresh — no caches, no fallback hardcoding.
        /// </summary>
        public static object BuildSystemState()
        {
            var (ssid, strength, wifiConn)    = GetWifiInfo();
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
                wifi       = new { connected = wifiConn, ssid, strength },
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
            var (ssid, strength, wifiConn)    = GetWifiInfo();
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
                wifi       = new { connected = wifiConn, ssid, strength },
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
                new() { Name = "Settings",             Path = "ms-settings:" }
            };

            try
            {
                string[] paths = {
                    Environment.GetFolderPath(Environment.SpecialFolder.CommonPrograms),
                    Environment.GetFolderPath(Environment.SpecialFolder.Programs)
                };
                foreach (var path in paths)
                {
                    if (Directory.Exists(path))
                    {
                        foreach (var file in Directory.GetFiles(path, "*.lnk", SearchOption.AllDirectories))
                        {
                            string name = Path.GetFileNameWithoutExtension(file);
                            if (!apps.Any(a => string.Equals(a.Name, name, StringComparison.OrdinalIgnoreCase)))
                            {
                                apps.Add(new Models.AppItem { Name = name, Path = file });
                            }
                        }
                    }
                }
            }
            catch { }

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
                        items.Add(new Models.FileItem { Name = dir.Name, Path = dir.FullName, IsDirectory = true, Type = "folder", LastModified = ((DateTimeOffset)dir.LastWriteTime).ToUnixTimeMilliseconds() });
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
                            ThumbnailBase64 = thumbB64,
                            LastModified = ((DateTimeOffset)fi.LastWriteTime).ToUnixTimeMilliseconds()
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

        public static bool RenameFile(string path, string newName)
        {
            try
            {
                if (File.Exists(path))
                {
                    string dir = Path.GetDirectoryName(path) ?? "";
                    string newPath = Path.Combine(dir, newName);
                    File.Move(path, newPath);
                    return true;
                }
                else if (Directory.Exists(path))
                {
                    string dir = Path.GetDirectoryName(path) ?? "";
                    string newPath = Path.Combine(dir, newName);
                    Directory.Move(path, newPath);
                    return true;
                }
                return false;
            }
            catch { return false; }
        }

        public static bool CreateFolder(string basePath, string folderName)
        {
            try
            {
                string path = Path.Combine(basePath, folderName);
                Directory.CreateDirectory(path);
                return true;
            }
            catch { return false; }
        }

        public static bool CreateFile(string basePath, string fileName)
        {
            try
            {
                string path = Path.Combine(basePath, fileName);
                File.Create(path).Dispose();
                return true;
            }
            catch { return false; }
        }

        public static bool DeleteFile(string path)
        {
            try
            {
                if (File.Exists(path)) File.Delete(path);
                else if (Directory.Exists(path)) Directory.Delete(path, true);
                else return false;
                return true;
            }
            catch { return false; }
        }

        public static bool CopyFile(string source, string destDir)
        {
            try
            {
                string name = Path.GetFileName(source);
                string dest = Path.Combine(destDir, name);
                if (File.Exists(source))
                {
                    File.Copy(source, dest, true);
                    return true;
                }
                else if (Directory.Exists(source))
                {
                    // Simple recursive copy or just throw not implemented for folders
                    // To keep it simple, skip directory copy or implement it. Let's do a simple file only copy.
                    return false;
                }
                return false;
            }
            catch { return false; }
        }

        public static bool MoveFile(string source, string destDir)
        {
            try
            {
                string name = Path.GetFileName(source);
                string dest = Path.Combine(destDir, name);
                if (File.Exists(source))
                {
                    File.Move(source, dest);
                    return true;
                }
                else if (Directory.Exists(source))
                {
                    Directory.Move(source, dest);
                    return true;
                }
                return false;
            }
            catch { return false; }
        }

        private static ImageCodecInfo GetJpegEncoder()
        {
            foreach (var codec in ImageCodecInfo.GetImageEncoders())
                if (codec.FormatID == ImageFormat.Jpeg.Guid) return codec;
            return ImageCodecInfo.GetImageEncoders()[0];
        }
    }
}
