using System;
using System.Collections.ObjectModel;
using System.ComponentModel;
using System.IO;
using System.Linq;
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
        private bool _usbModeActive = false;
        public bool UsbModeActive { get => _usbModeActive; set { _usbModeActive = value; OnPropertyChanged(); } }
        private readonly ScreenCaptureService _screenCapture = new();
        private readonly CameraService _cameraService = new();
        private readonly AudioStreamService _audioStreamService = new();
        private readonly ClipboardService _clipboardService = new();
        private readonly UdpDiscoveryService _udpDiscovery = new();
        public readonly LocalWebSocketServer LocalWsServer = new();
        public WebRtcManager WebRtcManager { get; }
        private bool _isLanConnected = false;
        private VirtualWorkspaceWindow? _virtualWorkspaceWindow;

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

        private string _connectionMode = "Cloud Relay";
        public string ConnectionMode { get => _connectionMode; set { _connectionMode = value; OnPropertyChanged(); } }

        // ─── Notifications ───
        public ObservableCollection<NotificationItem> Notifications { get; } = new();

        // ─── SMS ───
        public ObservableCollection<SmsThread> SmsThreads { get; } = new();
        public ObservableCollection<SmsMessage> CurrentThreadMessages { get; } = new();

        // ─── Trusted Devices ───
        public ObservableCollection<string> TrustedDevices { get; } = new ObservableCollection<string> {
            "Galaxy S24 Ultra",
            "Pixel 7 Pro (Offline)"
        };

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
            WebRtcManager = new WebRtcManager(SendData);
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

            LocalWsServer.MessageReceived += msgJson =>
            {
                try
                {
                    var msg = JObject.Parse(msgJson);
                    HandleMessage(msg);
                }
                catch { }
            };

            LocalWsServer.ClientConnected += () =>
            {
                _isLanConnected = true;
                ConnectionMode = "Direct LAN";
                IsConnected = true;
                StatusText = "Phone connected (LAN) ✓";
                StartPeriodicInfoSend();
            };

            LocalWsServer.ClientDisconnected += () =>
            {
                _isLanConnected = false;
                ConnectionMode = "Cloud Relay";
                if (!WsService.IsPhoneConnected)
                {
                    IsConnected = false;
                    StatusText = "Phone disconnected";
                    StopPeriodicInfoSend();
                }
            };
            
            // Start Local WebSocket server
            LocalWsServer.Start();
        }

        public void StartLanDiscovery(string deviceId)
        {
            _udpDiscovery.StartBroadcasting(deviceId, Environment.MachineName, LocalWsServer.Port);
        }

        public void SendData(object payload)
        {
            if (_isLanConnected)
                LocalWsServer.Broadcast(payload);
            else
                WsService.Send(payload);
        }

        public void SendDataRaw(string json)
        {
            if (_isLanConnected)
                LocalWsServer.Broadcast(json);
            else
                WsService.SendRaw(json);
        }

        private System.Threading.Timer? _wifiTimer;
        private System.Threading.Timer? _appsTimer;
        private System.Threading.Timer? _perfTimer;
        private System.Threading.Timer? _stateTimer;
        private int  _lastRunningAppsHash = 0;
        private string _lastWifiSsid = "";

        // ── Performance metrics — sent every 5s regardless of change ─────────
        private DateTime _lastPerfSentAt = DateTime.MinValue;

        private void StartPeriodicInfoSend()
        {
            StopPeriodicInfoSend();

            // ── WiFi: check every 3s, send ONLY when SSID changes ────────────
            _wifiTimer = new System.Threading.Timer(_ =>
            {
                if (!IsConnected) return;
                var (ssid, strength, connected) = SystemInfoService.GetWifiInfo();
                if (ssid != _lastWifiSsid)
                {
                    _lastWifiSsid = ssid;
                    SendData(new { type = "wifi_info", ssid, strength, connected });
                }
            }, null, 3000, 3000);

            // ── Running apps: check every 2s, send ONLY when window list changes ─
            _appsTimer = new System.Threading.Timer(_ =>
            {
                if (!IsConnected) return;
                var apps = SystemInfoService.GetRunningApps();
                int hash = string.Join(",", apps.Select(a => $"{a.Handle}:{a.Name}")).GetHashCode();
                if (hash != _lastRunningAppsHash)
                {
                    _lastRunningAppsHash = hash;
                    SendData(new { type = "state_update", running_apps = apps });
                }
            }, null, 2000, 2000);

            // ── Performance metrics: send every 5s (always, lightweight) ─────
            _perfTimer = new System.Threading.Timer(_ =>
            {
                if (!IsConnected) return;
                var perf = SystemInfoService.GetPerformanceMetrics();
                SendData(new { type = "state_update", performance = perf });
            }, null, 5000, 5000);

            // ── System State (battery, volume, brightness): send every 2s ────
            _stateTimer = new System.Threading.Timer(_ =>
            {
                if (!IsConnected) return;
                SendData(SystemInfoService.BuildStateUpdate());
            }, null, 2000, 2000);
        }

        private void StopPeriodicInfoSend()
        {
            _wifiTimer?.Dispose();  _wifiTimer  = null;
            _appsTimer?.Dispose();  _appsTimer  = null;
            _perfTimer?.Dispose();  _perfTimer  = null;
            _stateTimer?.Dispose(); _stateTimer = null;
        }

        private void HandleMessage(JObject msg)
        {
            var type = msg["type"]?.ToString() ?? "";
            switch (type)
            {
                case "handshake":
                    PhoneName = msg["device"]?.ToString() ?? "Android Phone";
                    Task.Run(async () => { 
                        SendSystemInfo(); 
                        await Task.Delay(500); 
                        SendAppList(); 
                        SendWallpaper();
                        SendData(new { type = "request_all_notifications" });
                    });
                    break;

                case "pong":
                    break;

                case "lock_pc":
                    SystemInfoService.LockPC();
                    break;

                case "power_command":
                    SystemInfoService.ExecutePowerCommand(msg["action"]?.ToString() ?? "");
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

                case "media_seek":
                    _ = MediaControlService.SetPlaybackPositionAsync(msg["position"]?.ToObject<double>() ?? 0);
                    break;

                case "app_list":
                    SendAppList();
                    break;

                case "launch_app":
                    string lPath = msg["appPath"]?.ToString() ?? "";
                    string lName = msg["appName"]?.ToString() ?? "";
                    string lHandle = msg["appHandle"]?.ToString() ?? "";
                    if (lPath == "##CLOSE##") {
                        SystemInfoService.CloseApp(lHandle);
                        SendRunningApps();
                    } else if (lPath == "##FOCUS##") {
                        SystemInfoService.FocusApp(lHandle);
                        SendRunningApps();
                    } else {
                        SystemInfoService.LaunchApp(string.IsNullOrEmpty(lPath) ? lName : lPath);
                    }
                    break;

                case "browse":
                    SendFileList(msg["path"]?.ToString() ?? "root");
                    break;

                case "open_file":
                    SystemInfoService.OpenFile(msg["path"]?.ToString() ?? "");
                    break;

                case "download_file":
                case "file_request":
                    SendFile(msg["path"]?.ToString() ?? "");
                    break;

                case "start_screen":
                    _ = WebRtcManager.StartScreenShareAsync();
                    break;
                case "start_screen_bound":
                    // Bound screen share could be implemented by cropping the captured bitmap in WebRtcManager
                    _ = WebRtcManager.StartScreenShareAsync();
                    break;
                case "stop_screen":
                    WebRtcManager.StopStream();
                    System.Windows.Application.Current.Dispatcher.Invoke(() => {
                        _virtualWorkspaceWindow?.Close();
                        _virtualWorkspaceWindow = null;
                    });
                    break;
                case "start_screen_extend":
                    // Extended workspace could also use WebRtcManager by capturing the specific window
                    _ = WebRtcManager.StartScreenShareAsync(); 
                    break;
                case "start_camera":
                    _ = WebRtcManager.StartCameraAsync();
                    break;
                case "stop_camera":
                    WebRtcManager.StopStream();
                    break;

                case "clipboard_push":
                case "clipboard_sync":
                    ClipboardService.SetClipboardText(msg["content"]?.ToString() ?? "");
                    break;

                case "clipboard_pull":
                    SendData(new { type = "clipboard_pull", content = ClipboardService.GetClipboardText() });
                    break;

                case "get_wallpaper":
                case "request_info":
                    SendSystemInfo();
                    SendWallpaper();
                    SendRunningApps();
                    break;

                case "notification":
                case "send_notification":
                    App.Current.Dispatcher.Invoke(() =>
                    {
                        var item = new NotificationItem
                        {
                            AppName   = msg["app"]?.ToString() ?? "",
                            Title     = msg["title"]?.ToString() ?? "",
                            Body      = msg["body"]?.ToString() ?? "",
                            Key       = msg["key"]?.ToString() ?? "",
                            Timestamp = DateTime.Now
                        };
                        Notifications.Insert(0, item);
                        if (Notifications.Count > 50) Notifications.RemoveAt(Notifications.Count - 1);
                        
                        // Show Popup if not a sync-load
                        if (msg["type"]?.ToString() == "notification")
                        {
                            ShowPopup(item.Title, item.Body);
                        }
                    });
                    break;

                case "mobile_wallpaper":
                    App.Current.Dispatcher.Invoke(() => {
                        var data = msg["data"]?.ToString();
                        if (!string.IsNullOrEmpty(data)) {
                            try {
                                var bytes = Convert.FromBase64String(data);
                                var bmp = new BitmapImage();
                                bmp.BeginInit();
                                bmp.StreamSource = new MemoryStream(bytes);
                                bmp.EndInit();
                                (App.Current.MainWindow as MainWindow)?.UpdateMobileWallpaper(bmp);
                            } catch { }
                        }
                    });
                    break;

                // ── USB Touchpad / Mouse control ───────────────────────────────
                case "mouse_move":
                {
                    var dx = msg["dx"]?.ToObject<float>() ?? 0f;
                    var dy = msg["dy"]?.ToObject<float>() ?? 0f;
                    MouseControlService.MoveRelative(dx, dy);
                    break;
                }
                case "mouse_tap":
                    MouseControlService.LeftClick();
                    break;

                case "mouse_right_tap":
                    MouseControlService.RightClick();
                    break;

                case "mouse_middle_tap":
                    MouseControlService.MiddleClick();
                    break;

                case "mouse_scroll":
                {
                    var isHorizontal = msg["horizontal"]?.ToObject<bool>() ?? false;
                    if (isHorizontal)
                    {
                        var dx = msg["dx"]?.ToObject<float>() ?? 0f;
                        MouseControlService.HScroll(dx);
                    }
                    else
                    {
                        var dy = msg["dy"]?.ToObject<float>() ?? 0f;
                        MouseControlService.Scroll(dy);
                    }
                    break;
                }

                case "key_press":
                    MouseControlService.SendKeyPress(msg["key"]?.ToString() ?? "");
                    break;
                case "usb_connected":
                    UsbModeActive = true;
                    StatusText = "USB Touchpad mode active";
                    SendData(new { type = "usb_connected" });
                    break;

                case "usb_disconnected":
                    UsbModeActive = false;
                    StatusText = "USB disconnected — WebSocket mode";
                    SendData(new { type = "usb_disconnected" });
                    break;

                // WebRTC signaling
                case "webrtc_offer":
                case "webrtc_answer":
                case "webrtc_ice":
                    _ = WebRtcManager.HandleSignalingMessageAsync(msg);
                    break;

                case "sms_received":
                case "photo_list":
                    break;
            }
        }

        private void SendSystemInfo()
        {
            // Delegate to consolidated BuildSystemState — all real values, one call
            SendData(SystemInfoService.BuildSystemState());
        }

        private void SendWallpaper()
        {
            Task.Run(async () =>
            {
                await Task.Delay(3000);
                var (b64, _) = SystemInfoService.GetWallpaperBase64Cached();
                if (!string.IsNullOrEmpty(b64) && IsConnected)
                    SendData(new { type = "wallpaper", data = b64 });
            });
        }

        private void SendAppList()
        {
            var apps = SystemInfoService.GetInstalledApps();
            SendData(new { type = "app_list", apps });
        }

        private void SendRunningApps()
        {
            var apps = SystemInfoService.GetRunningApps();
            var perf = SystemInfoService.GetPerformanceMetrics();
            _lastRunningAppsHash = string.Join(",", apps.Select(a => a.Name)).GetHashCode();
            SendData(new { type = "state_update", running_apps = apps, performance = perf });
        }

        private void SendFileList(string path)
        {
            var files = SystemInfoService.BrowsePath(path);
            SendData(new { type = "file_list", files });
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
                        SendData(new
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
                    SendData(new { type = "error", message = $"File transfer failed: {ex.Message}" });
                }
            });
        }

        private void SetupClipboard()
        {
            _clipboardService.ClipboardChanged += content =>
            {
                if (!string.IsNullOrEmpty(content))
                    SendData(new { type = "clipboard_push", content });
            };
            _clipboardService.Start();
        }

        // ── Now Playing — fingerprint-gated, only sends when something changes ──
        private string _lastNpFingerprint = "";
        private bool   _lastNpIsPlaying   = false;
        private double _lastNpPositionSentAt = -999; // position threshold: resend every 3s if playing

        private void StartNowPlayingPolling()
        {
            Task.Run(async () =>
            {
                while (true)
                {
                    try
                    {
                        if (IsConnected)
                        {
                            var np = await MediaControlService.GetNowPlayingAsync();

                            // Fingerprint: title + artist + isPlaying + shuffle + repeat
                            var fingerprint = $"{np.Title}|{np.Artist}|{np.IsPlaying}|{np.ShuffleActive}|{np.RepeatMode}|{np.DurationSec:F0}";

                            // Position: resend every ~3 seconds while playing to keep timeline sync
                            bool positionDrifted = np.IsPlaying &&
                                                   Math.Abs(np.PositionSec - _lastNpPositionSentAt) > 3.0;

                            bool changed = fingerprint != _lastNpFingerprint || positionDrifted;

                            if (changed)
                            {
                                _lastNpFingerprint    = fingerprint;
                                _lastNpIsPlaying      = np.IsPlaying;
                                _lastNpPositionSentAt = np.PositionSec;

                                NowPlayingTitle  = np.Title;
                                NowPlayingArtist = np.Artist;

                                SendData(new
                                {
                                    type             = "now_playing",
                                    title            = np.Title,
                                    artist           = np.Artist,
                                    album_art_base64 = np.AlbumArtBase64,
                                    isPlaying        = np.IsPlaying,
                                    position         = np.PositionSec,
                                    duration         = np.DurationSec,
                                    appSource        = np.AppSource,
                                    shuffleActive    = np.ShuffleActive,
                                    repeatMode       = np.RepeatMode,
                                });
                            }
                        }
                    }
                    catch (Exception ex)
                    {
                        Console.WriteLine($"[NowPlaying] Poll error: {ex.Message}");
                    }
                    await Task.Delay(1000);
                }
            });
        }

        /// <summary>
        /// Generates QR code for cloud-only pairing.
        /// Payload: { userId, deviceId, deviceName, pairId, relayUrl }
        /// Android scans this and connects directly to the Render relay.
        /// </summary>
        public void GenerateQRCode(string userId, string deviceId, string deviceName)
        {
            var payload = JsonConvert.SerializeObject(new
            {
                userId,
                deviceId,
                deviceName,
                pairId   = _pairId,
                relayUrl = WebSocketService.RelayServerUrl,
            });
            QrCodeImage = QRCodeService.GenerateQRCode(payload, pixelSize: 8);
        }

        public void ClearMobileNotification(NotificationItem item)
        {
            if (item == null) return;
            Notifications.Remove(item);
            SendData(new { type = "clear_mobile_notification", key = item.Key });
        }

        private void ShowPopup(string title, string body)
        {
            (App.Current.MainWindow as MainWindow)?.ShowNotifPopup(title, body);
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
