using System;
using System.Collections.ObjectModel;
using System.ComponentModel;
using System.IO;
using System.Runtime.CompilerServices;
using System.Threading.Tasks;
using System.Windows.Media.Imaging;
using NexLink.Models;
using NexLink.Services;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;

namespace NexLink.ViewModels
{
    public class MainViewModel : INotifyPropertyChanged
    {
        // ─── Services ───
        public readonly WebSocketService WsService = new();
        private readonly ScreenCaptureService _screenCapture = new();
        private readonly CameraService _cameraService = new();
        private readonly ClipboardService _clipboardService = new();

        // ─── Connection ───
        private bool _isConnected;
        public bool IsConnected { get => _isConnected; set { _isConnected = value; OnPropertyChanged(); OnPropertyChanged(nameof(ConnectionStatusText)); OnPropertyChanged(nameof(ConnectionDotColor)); } }
        public string ConnectionStatusText => IsConnected ? "Connected" : "Waiting for phone...";
        public string ConnectionDotColor => IsConnected ? "#3FB950" : "#DA3633";

        // ─── Phone info ───
        private string _phoneName = "Android Phone";
        public string PhoneName { get => _phoneName; set { _phoneName = value; OnPropertyChanged(); } }

        private int _phoneBattery;
        public int PhoneBattery { get => _phoneBattery; set { _phoneBattery = value; OnPropertyChanged(); } }

        private string _phoneWifi = "—";
        public string PhoneWifi { get => _phoneWifi; set { _phoneWifi = value; OnPropertyChanged(); } }

        // ─── QR Code ───
        private BitmapImage? _qrCodeImage;
        public BitmapImage? QrCodeImage { get => _qrCodeImage; set { _qrCodeImage = value; OnPropertyChanged(); } }

        // ─── Pairing / Relay ───
        private string _pairId = "";
        public string PairId { get => _pairId; set { _pairId = value; OnPropertyChanged(); } }

        private string _connectionMode = "Local WiFi";
        public string ConnectionMode { get => _connectionMode; set { _connectionMode = value; OnPropertyChanged(); } }

        // ─── Notifications ───
        public ObservableCollection<NotificationItem> Notifications { get; } = new();

        // ─── SMS ───
        public ObservableCollection<SmsThread> SmsThreads { get; } = new();
        public ObservableCollection<SmsMessage> CurrentThreadMessages { get; } = new();

        private SmsThread? _selectedThread;
        public SmsThread? SelectedThread { get => _selectedThread; set { _selectedThread = value; OnPropertyChanged(); } }

        // ─── Photos ───
        public ObservableCollection<PhotoItem> Photos { get; } = new();

        // ─── Status ───
        private string _statusText = "Ready";
        public string StatusText { get => _statusText; set { _statusText = value; OnPropertyChanged(); } }

        // ─── Now Playing ───
        private string _nowPlayingTitle = "Not Playing";
        public string NowPlayingTitle { get => _nowPlayingTitle; set { _nowPlayingTitle = value; OnPropertyChanged(); } }
        private string _nowPlayingArtist = "";
        public string NowPlayingArtist { get => _nowPlayingArtist; set { _nowPlayingArtist = value; OnPropertyChanged(); } }

        public MainViewModel()
        {
            SetupWebSocket();
            SetupClipboard();
            StartNowPlayingPolling();
        }

        private void SetupWebSocket()
        {
            WsService.PhoneConnected += () =>
            {
                IsConnected = true;
                StatusText = "Phone connected ✓";
                // Data is sent from handshake handler - avoids pipe overload
                StartPeriodicInfoSend();
            };

            WsService.PhoneDisconnected += () =>
            {
                IsConnected = false;
                StatusText = "Phone disconnected";
                StopPeriodicInfoSend();
            };

            WsService.MessageReceived += HandleMessage;
        }

        private System.Threading.Timer? _infoTimer;
        private System.Threading.Timer? _wifiTimer;
        private string _lastWifiSsid = "";

        private void StartPeriodicInfoSend()
        {
            StopPeriodicInfoSend();
            // Full info every 15 seconds
            _infoTimer = new System.Threading.Timer(_ =>
            {
                if (IsConnected) SendSystemInfo();
            }, null, 15000, 15000);

            // WiFi SSID change detection every 2 seconds
            _wifiTimer = new System.Threading.Timer(_ =>
            {
                if (!IsConnected) return;
                var ssid = WebSocketService.GetWifiSSID();
                if (ssid != _lastWifiSsid)
                {
                    _lastWifiSsid = ssid;
                    WsService.Send(new { type = "wifi_info", ssid, strength = 80 });
                }
            }, null, 2000, 2000);
        }

        private void StopPeriodicInfoSend()
        {
            _infoTimer?.Dispose(); _infoTimer = null;
            _wifiTimer?.Dispose(); _wifiTimer = null;
        }

        private void HandleMessage(JObject msg)
        {
            var type = msg["type"]?.ToString() ?? "";
            switch (type)
            {
                case "handshake":
                    PhoneName = msg["device"]?.ToString() ?? "Android Phone";
                    // Send back system info immediately on handshake
                    Task.Run(async () => { SendSystemInfo(); await System.Threading.Tasks.Task.Delay(500); SendAppList(); SendWallpaper(); });
                    break;
                case "pong":
                    break; // ignore heartbeat responses
                case "lock_pc":
                    SystemInfoService.LockPC();
                    break;
                case "volume":
                    SystemInfoService.SetVolume(msg["level"]?.ToObject<int>() ?? 50);
                    break;
                case "brightness":
                    SystemInfoService.SetBrightness(msg["level"]?.ToObject<int>() ?? 50);
                    break;
                case "media_control":
                    MediaControlService.SendMediaKey(msg["action"]?.ToString() ?? "play_pause");
                    break;
                case "app_list":
                    SendAppList();
                    break;
                case "launch_app":
                    SystemInfoService.LaunchApp(msg["appPath"]?.ToString() ?? msg["appName"]?.ToString() ?? "");
                    break;
                case "browse":
                    SendFileList(msg["path"]?.ToString() ?? "root");
                    break;
                case "open_file":
                    SystemInfoService.OpenFile(msg["path"]?.ToString() ?? "");
                    break;
                case "download_file":
                    SendFile(msg["path"]?.ToString() ?? "");
                    break;
                case "start_screen":
                    _screenCapture.StartStreaming(b64 =>
                        WsService.Send(new { type = "screen_frame", data = b64 }), 500);
                    break;
                case "stop_screen":
                    _screenCapture.StopStreaming();
                    break;
                case "start_camera":
                    _cameraService.StartStreaming(b64 =>
                        WsService.Send(new { type = "camera_frame", data = b64 }), 100);
                    break;
                case "stop_camera":
                    _cameraService.StopStreaming();
                    break;
                case "clipboard_push":
                    ClipboardService.SetClipboardText(msg["content"]?.ToString() ?? "");
                    break;
                case "clipboard_pull":
                    WsService.Send(new { type = "clipboard_pull", content = ClipboardService.GetClipboardText() });
                    break;
                case "get_wallpaper":
                case "request_info":
                    SendSystemInfo();
                    SendWallpaper();
                    break;
                case "notification":
                    App.Current.Dispatcher.Invoke(() =>
                    {
                        Notifications.Insert(0, new NotificationItem
                        {
                            App = msg["app"]?.ToString() ?? "",
                            Title = msg["title"]?.ToString() ?? "",
                            Body = msg["body"]?.ToString() ?? "",
                            Timestamp = DateTime.Now
                        });
                        if (Notifications.Count > 50) Notifications.RemoveAt(Notifications.Count - 1);
                    });
                    break;
                case "sms_received":
                    // Handle incoming SMS forwarded from phone
                    break;
                case "photo_list":
                    // Phone is pushing photo list
                    break;
            }
        }

        private void SendSystemInfo()
        {
            var wifiSsid = WebSocketService.GetWifiSSID();
            var (battLevel, battCharging) = SystemInfoService.GetBatteryInfo();
            WsService.Send(new { type = "wifi_info", ssid = wifiSsid, strength = 80 });
            WsService.Send(new { type = "battery_info", level = battLevel, isCharging = battCharging });
            WsService.Send(new { type = "bt_info", devices = SystemInfoService.GetBluetoothDevices() });
        }

        private void SendWallpaper()
        {
            Task.Run(async () =>
            {
                // Delay slightly to avoid overwhelming a freshly established connection
                await Task.Delay(3000);
                var b64 = ScreenCaptureService.GetWallpaperBase64();
                if (!string.IsNullOrEmpty(b64) && IsConnected)
                    WsService.Send(new { type = "wallpaper", data = b64 });
            });
        }

        private void SendAppList()
        {
            var apps = SystemInfoService.GetInstalledApps();
            WsService.Send(new { type = "app_list", apps });
        }

        private void SendFileList(string path)
        {
            var files = SystemInfoService.BrowsePath(path);
            WsService.Send(new { type = "file_list", files });
        }

        private void SendFile(string path)
        {
            Task.Run(() =>
            {
                try
                {
                    var bytes = File.ReadAllBytes(path);
                    const int chunkSize = 64 * 1024; // 64KB
                    int total = (int)Math.Ceiling((double)bytes.Length / chunkSize);
                    for (int i = 0; i < total; i++)
                    {
                        int offset = i * chunkSize;
                        int len = Math.Min(chunkSize, bytes.Length - offset);
                        var chunk = new byte[len];
                        Array.Copy(bytes, offset, chunk, 0, len);
                        WsService.Send(new
                        {
                            type = "file_chunk",
                            name = Path.GetFileName(path),
                            index = i,
                            totalChunks = total,
                            data = Convert.ToBase64String(chunk),
                            progress = (float)(i + 1) / total
                        });
                    }
                }
                catch (Exception ex)
                {
                    WsService.Send(new { type = "error", message = $"File transfer failed: {ex.Message}" });
                }
            });
        }

        private void SetupClipboard()
        {
            _clipboardService.ClipboardChanged += content =>
            {
                if (!string.IsNullOrEmpty(content))
                    WsService.Send(new { type = "clipboard_push", content });
            };
            _clipboardService.Start();
        }

        private void StartNowPlayingPolling()
        {
            Task.Run(async () =>
            {
                while (true)
                {
                    if (IsConnected)
                    {
                        var (title, artist, albumArt, isPlaying) = await MediaControlService.GetNowPlayingAsync();
                        NowPlayingTitle = title;
                        NowPlayingArtist = artist;
                        WsService.Send(new
                        {
                            type = "now_playing",
                            title,
                            artist,
                            album_art_base64 = albumArt,
                            isPlaying
                        });
                    }
                    await Task.Delay(5000);
                }
            });
        }

        /// <summary>
        /// Generates QR with BOTH local IP and relay pairId.
        /// Android app will try local first, then fall back to relay.
        /// </summary>
        public void GenerateQRCode(string ip, int port, string deviceName, string token)
        {
            var payload = JsonConvert.SerializeObject(new
            {
                ip,
                port,
                deviceName,
                sessionToken = token,
                pairId = _pairId,
                relayUrl = Services.WebSocketService.RelayServerUrl
            });
            QrCodeImage = QRCodeService.GenerateQRCode(payload, pixelSize: 8);
        }

        public void SetPairId(string pairId)
        {
            _pairId = pairId;
            PairId = pairId;
        }

        public event PropertyChangedEventHandler? PropertyChanged;
        protected void OnPropertyChanged([CallerMemberName] string? name = null)
            => App.Current.Dispatcher.Invoke(() => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name)));
    }
}
