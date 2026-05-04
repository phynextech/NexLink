using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Threading;
using NexLink.Models;
using NexLink.Services;
using NexLink.ViewModels;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;

namespace NexLink
{
    public partial class MainWindow : Window
    {
        private readonly MainViewModel _vm = new();
        private readonly List<ClipboardItem> _clipboardItems = new();

        // Cloud identity
        private string _userId   = "";
        private string _deviceId = "";

        // 2-second broadcast timer
        private DispatcherTimer? _broadcastTimer;

        // 5-second wallpaper sync timer
        private DispatcherTimer? _wallpaperTimer;

        // Clipboard monitoring
        private string _lastClipboardHash = "";
        private DispatcherTimer? _clipboardTimer;

        // Track last-known values so we push changes only when something actually changed
        private int _lastKnownVolume = -1;
        private int _lastKnownBrightness = -1;
        private int _lastKnownBattery = -1;
        private bool _lastKnownCharging = false;
        private string _lastKnownSsid = "";
        private bool _lastKnownMuted = false;

        public MainWindow()
        {
            InitializeComponent();
            DataContext = _vm;
            Loaded += OnLoaded;
        }

        // ─── Startup ───
        private async void OnLoaded(object sender, RoutedEventArgs e)
        {
            StartServer();
            await InitRelayPairingAsync();
            PopulateAppsTab();
            StartBroadcastTimer();
            StartWallpaperTimer();
            StartClipboardWatcher();
        }

        private async Task InitRelayPairingAsync()
        {
            try
            {
                // Load or create persistent device identity for this PC
                (_userId, _deviceId) = PairingService.LoadOrCreateIdentity();

                // Show QR immediately with empty pairId so user sees something
                GenerateQR();

                // Register device on the relay server and get a pairId
                var pairId = await PairingService.GetOrCreatePairIdAsync(_userId, _deviceId, Environment.MachineName);
                if (!string.IsNullOrEmpty(pairId))
                {
                    _vm.SetPairId(pairId);
                    // Regenerate QR now that pairId is available
                    GenerateQR();
                    // Connect to relay as "desktop"
                    _vm.WsService.ConnectToRelay(_userId, _deviceId);
                    StatusBar.Text = $"Cloud relay connected • Device: {_deviceId[..8]}…";
                }
            }
            catch (Exception ex)
            {
                StatusBar.Text = $"Relay unavailable: {ex.Message}";
                // Still connect without pairId — room key is userId:deviceId
                if (!string.IsNullOrEmpty(_userId))
                    _vm.WsService.ConnectToRelay(_userId, _deviceId);
            }
        }

        private void StartServer()
        {
            try
            {
                PortLabel.Text = WebSocketService.RelayServerUrl;

                _vm.WsService.PhoneConnected += () => Dispatcher.Invoke(() =>
                {
                    DisconnectedOverlay.Visibility = Visibility.Collapsed;
                    ConnectedDashboard.Visibility = Visibility.Visible;
                    PhoneInfoPanel.Visibility = Visibility.Visible;
                    PhoneNameLabel.Text = _vm.PhoneName;
                    SetStatusConnected(true);
                    StatusBar.Text = "Phone connected";
                    // Immediately send ALL real system values
                    Task.Run(() => SendSystemState());
                });

                _vm.WsService.PhoneDisconnected += () =>
                {
                    // Wait 8 seconds to allow silent auto-reconnect
                    Task.Delay(8000).ContinueWith(_ =>
                    {
                        if (!_vm.WsService.IsPhoneConnected)
                        {
                            Dispatcher.Invoke(() =>
                            {
                                // Stay on the dashboard, just update status
                                SetStatusConnected(false);
                                StatusBar.Text = "Mobile app disconnected";
                                PhoneNameLabel.Text = "Offline";
                            });
                        }
                    });
                };

                _vm.WsService.MessageReceived += msg => Dispatcher.Invoke(() =>
                {
                    var type = msg["type"]?.ToString() ?? "";
                    switch (type)
                    {
                        case "notification":
                            var notif = new NotificationItem
                            {
                                App   = msg["app"]?.ToString()   ?? "",
                                Title = msg["title"]?.ToString() ?? "",
                                Body  = msg["body"]?.ToString()  ?? "",
                                Timestamp = DateTime.Now
                            };
                            _vm.Notifications.Insert(0, notif);
                            NotifList.ItemsSource = _vm.Notifications;
                            NotifCount.Text = _vm.Notifications.Count.ToString();
                            break;

                        case "now_playing":
                            NowPlayingTitle.Text  = msg["title"]?.ToString()  ?? "Not Playing";
                            NowPlayingArtist.Text = msg["artist"]?.ToString() ?? "";
                            break;

                        case "battery_info":
                            var lvl      = msg["level"]?.ToObject<int>() ?? 0;
                            var charging = msg["isCharging"]?.ToObject<bool>() ?? false;
                            BatteryLabel.Text = $"{lvl}%{(charging ? " ⚡" : "")}";
                            break;

                        case "wifi_info":
                            WifiLabel.Text = msg["ssid"]?.ToString() ?? "—";
                            break;

                        case "sms_list":
                        case "mobile_sms_list":
                            var threads = msg["threads"]?.ToObject<List<SmsThread>>();
                            if (threads != null)
                            {
                                _vm.SmsThreads.Clear();
                                foreach (var t in threads) _vm.SmsThreads.Add(t);
                                SmsThreadList.ItemsSource = _vm.SmsThreads;
                                SmsThreadCount.Text = threads.Count.ToString();
                            }
                            break;

                        case "photo_list":
                        case "mobile_photo_list":
                            var albums = msg["albums"]?.ToObject<List<PhotoAlbum>>();
                            if (albums != null)
                                Dispatcher.Invoke(() => PopulateAlbums(albums));
                            else
                            {
                                // Fallback: flat photo list
                                var photos = msg["photos"]?.ToObject<List<PhotoItem>>();
                                if (photos != null) PopulatePhotos(photos);
                            }
                            break;

                        case "mobile_photo_thumbnail":
                            var thumbPath = msg["path"]?.ToString() ?? "";
                            var thumbData = msg["thumbnail"]?.ToString() ?? "";
                            if (!string.IsNullOrEmpty(thumbPath) && !string.IsNullOrEmpty(thumbData))
                                ApplyPhotoThumbnail(thumbPath, thumbData);
                            break;

                        case "thread_messages":
                            var threadId  = msg["threadId"]?.ToString() ?? "";
                            var msgList   = msg["messages"]?.ToObject<List<SmsMessage>>();
                            if (msgList != null)
                            {
                                var thread = _vm.SmsThreads.FirstOrDefault(t => t.Id == threadId);
                                if (thread != null)
                                {
                                    thread.Messages = msgList;
                                    MessagesList.ItemsSource = msgList;
                                    MessagesScroll.ScrollToEnd();
                                }
                            }
                            break;

                        case "clipboard_push":
                        case "clipboard_sync":
                            var clipContent = msg["content"]?.ToString() ?? "";
                            if (!string.IsNullOrEmpty(clipContent))
                            {
                                // Set Windows clipboard
                                try { System.Windows.Clipboard.SetText(clipContent); } catch { }
                                _clipboardItems.Insert(0, new ClipboardItem { Content = clipContent });
                                ClipboardList.ItemsSource = null;
                                ClipboardList.ItemsSource = _clipboardItems;
                                // Update last hash so we don't echo back
                                _lastClipboardHash = clipContent.GetHashCode().ToString();
                            }
                            // Handle image clipboard from phone
                            var imgData = msg["image"]?.ToString();
                            if (!string.IsNullOrEmpty(imgData))
                            {
                                try
                                {
                                    var bytes = Convert.FromBase64String(imgData);
                                    using var ms = new System.IO.MemoryStream(bytes);
                                    var bmp = new System.Windows.Media.Imaging.BitmapImage();
                                    bmp.BeginInit(); bmp.StreamSource = ms; bmp.CacheOption = System.Windows.Media.Imaging.BitmapCacheOption.OnLoad; bmp.EndInit();
                                    System.Windows.Clipboard.SetImage(bmp);
                                    _lastClipboardHash = imgData.GetHashCode().ToString();
                                } catch { }
                            }
                            break;

                        case "mobile_wallpaper":
                            var wallData = msg["data"]?.ToString();
                            if (!string.IsNullOrEmpty(wallData))
                            {
                                try
                                {
                                    var bytes = Convert.FromBase64String(wallData);
                                    using var ms = new System.IO.MemoryStream(bytes);
                                    var bmp = new System.Windows.Media.Imaging.BitmapImage();
                                    bmp.BeginInit(); bmp.StreamSource = ms; bmp.CacheOption = System.Windows.Media.Imaging.BitmapCacheOption.OnLoad; bmp.EndInit();
                                    MobileWallpaperImage.Source = bmp;
                                } catch { }
                            }
                            break;

                        case "mobile_status":
                            var ringerMode  = msg["ringerMode"]?.ToObject<int>() ?? 2;
                            var phoneVol    = msg["phoneVolume"]?.ToObject<int>() ?? 0;
                            var ringerVol   = msg["ringerVolume"]?.ToObject<int>() ?? 0;
                            var notifCount  = msg["notifCount"]?.ToObject<int>() ?? 0;
                            // Ringer mode label
                            var ringerLabel = ringerMode switch { 0 => "🔇 Silent", 1 => "📳 Vibrate", _ => "🔔 Ring" };
                            MobileRingerLabel.Text    = ringerLabel;
                            MobileVolumeLabel.Text    = $"{phoneVol}%";
                            MobileRingerVolLabel.Text = $"{ringerVol}%";
                            MobileNotifCountLabel.Text = notifCount.ToString();
                            
                            // Only update slider if user isn't currently dragging it
                            if (!MobileMediaVolSlider.IsMouseCaptureWithin)
                                MobileMediaVolSlider.Value = phoneVol;
                            if (!MobileRingerVolSlider.IsMouseCaptureWithin)
                                MobileRingerVolSlider.Value = ringerVol;

                            // Highlight the active ringer button
                            RingerSilentBtn.Opacity  = ringerMode == 0 ? 1.0 : 0.4;
                            RingerVibrateBtn.Opacity = ringerMode == 1 ? 1.0 : 0.4;
                            RingerRingBtn.Opacity    = ringerMode == 2 ? 1.0 : 0.4;
                            break;

                        case "volume":
                            var newVol = msg["level"]?.ToObject<int>() ?? -1;
                            if (newVol >= 0)
                            {
                                SystemInfoService.SetVolume(newVol);
                                // Read back ACTUAL volume after setting (hardware may round)
                                var actualVol = SystemInfoService.GetVolume();
                                _lastKnownVolume = actualVol;  // update tracker to prevent re-push
                                ShowWindowsOsd("🔊", $"Volume: {actualVol}%");
                                // Confirm back with the real hardware-verified value
                                _vm.WsService.Send(new { type = "volume_ack", level = actualVol });
                            }
                            break;

                        case "brightness":
                            var newBri = msg["level"]?.ToObject<int>() ?? -1;
                            if (newBri >= 0)
                            {
                                SystemInfoService.SetBrightness(newBri);
                                // Read back ACTUAL brightness after setting
                                var actualBri = SystemInfoService.GetBrightness();
                                if (actualBri < 0) actualBri = newBri; // if WMI fails, trust the request
                                _lastKnownBrightness = actualBri;  // update tracker to prevent re-push
                                ShowWindowsOsd("☀", $"Brightness: {actualBri}%");
                                // Confirm back with the real hardware-verified value
                                _vm.WsService.Send(new { type = "brightness_ack", level = actualBri });
                            }
                            break;

                        case "media_control":
                            var action = msg["action"]?.ToString() ?? "";
                            if (action == "shuffle")
                                _ = MediaControlService.ToggleShuffleAsync();
                            else if (action == "repeat")
                                _ = MediaControlService.ToggleRepeatAsync();
                            else
                                MediaControlService.SendMediaKey(action);
                            break;

                        case "media_seek":
                            var pos = msg["position"]?.ToObject<double>() ?? 0;
                            _ = MediaControlService.SetPlaybackPositionAsync(pos);
                            break;

                        case "browse":
                            var browsePath = msg["path"]?.ToString() ?? "root";
                            Task.Run(() =>
                            {
                                try
                                {
                                    var files = SystemInfoService.BrowsePath(browsePath);
                                    var fileJson = new JObject
                                    {
                                        ["type"]  = "file_list",
                                        ["path"]  = browsePath,
                                        ["files"] = Newtonsoft.Json.Linq.JArray.FromObject(files)
                                    };
                                    _vm.WsService.SendRaw(fileJson.ToString());
                                }
                                catch (Exception ex)
                                {
                                    Console.WriteLine($"[Browse] Error: {ex.Message}");
                                    _vm.WsService.Send(new { type = "file_list", path = browsePath, files = Array.Empty<object>() });
                                }
                            });
                            break;

                        case "file_preview":
                            var previewPath = msg["path"]?.ToString() ?? "";
                            Task.Run(() =>
                            {
                                var preview = SystemInfoService.GetFilePreviewBase64(previewPath);
                                _vm.WsService.Send(new { type = "file_preview_data", path = previewPath, data = preview ?? "" });
                            });
                            break;

                        case "download_file":
                            var dlPath = msg["path"]?.ToString() ?? "";
                            _ = Task.Run(() => SystemInfoService.SendFileChunkedAsync(dlPath, chunk =>
                            {
                                _vm.WsService.SendRaw(chunk.ToString());
                            }));
                            break;

                        case "rename_file":
                            var renamePath = msg["path"]?.ToString() ?? "";
                            var newName = msg["newName"]?.ToString() ?? "";
                            SystemInfoService.RenameFile(renamePath, newName);
                            break;

                        case "create_folder":
                            var createFolderBase = msg["path"]?.ToString() ?? "";
                            var createFolderName = msg["name"]?.ToString() ?? "";
                            SystemInfoService.CreateFolder(createFolderBase, createFolderName);
                            break;

                        case "create_file":
                            var createFileBase = msg["path"]?.ToString() ?? "";
                            var createFileName = msg["name"]?.ToString() ?? "";
                            SystemInfoService.CreateFile(createFileBase, createFileName);
                            break;

                        case "delete_file":
                            var deletePath = msg["path"]?.ToString() ?? "";
                            SystemInfoService.DeleteFile(deletePath);
                            break;

                        case "copy_file":
                            var copySource = msg["source"]?.ToString() ?? "";
                            var copyDestDir = msg["destDir"]?.ToString() ?? "";
                            SystemInfoService.CopyFile(copySource, copyDestDir);
                            break;

                        case "move_file":
                            var moveSource = msg["source"]?.ToString() ?? "";
                            var moveDestDir = msg["destDir"]?.ToString() ?? "";
                            SystemInfoService.MoveFile(moveSource, moveDestDir);
                            break;

                        case "request_info":
                        case "get_wallpaper":
                            // Send full system_state with ALL real values, log each one
                            _ = Task.Run(() =>
                            {
                                try
                                {
                                    Console.WriteLine("[request_info] Reading system state...");
                                    var vol = SystemInfoService.GetVolume();
                                    var bri = SystemInfoService.GetBrightness();
                                    var (ssid, sig, wifiConn) = SystemInfoService.GetWifiInfo();
                                    var (batPct, charging, hasBat) = SystemInfoService.GetBatteryInfo();
                                    var muted = SystemInfoService.GetMuted();
                                    Console.WriteLine($"[request_info] volume={vol} brightness={bri} wifi='{ssid}' battery={batPct}% charging={charging} muted={muted}");
                                    // Now send the full bundled state_state
                                    SendSystemState();
                                }
                                catch (Exception ex)
                                {
                                    Console.WriteLine($"[request_info] ERROR: {ex.Message}");
                                }
                            });
                            break;
                    }
                });
            }
            catch (Exception ex)
            {
                MessageBox.Show($"Failed to start WebSocket server: {ex.Message}",
                    "LinkBridge", MessageBoxButton.OK, MessageBoxImage.Error);
            }
        }

        // ─── 2-Second Broadcast Timer ───
        private void StartBroadcastTimer()
        {
            /*
            _broadcastTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(2) };
            _broadcastTimer.Tick += (_, _) =>
            {
                if (_vm.WsService.IsPhoneConnected)
                    BroadcastSystemInfo();
            };
            _broadcastTimer.Start();
            */
        }

        /// <summary>
        /// Sends a FULL system_state to the phone (all real values, including wallpaper).
        /// Called once immediately on connect and on request_info.
        /// Also sends each piece of data as INDIVIDUAL named events — these are already
        /// in the relay server's event list, so they work even if the bundled system_state
        /// event hasn't been added to the relay yet.
        /// </summary>
        private void SendSystemState()
        {
            try
            {
                // Read all values fresh
                var vol  = SystemInfoService.GetVolume();
                var bri  = SystemInfoService.GetBrightness();
                var (ssid, sig, wifiConn) = SystemInfoService.GetWifiInfo();
                var (batPct, charging, hasBat) = SystemInfoService.GetBatteryInfo();
                var muted = SystemInfoService.GetMuted();
                var btDevices = SystemInfoService.GetBluetoothDevices();
                var btEnabled = SystemInfoService.GetBluetoothEnabled();
                var (wallB64, _) = SystemInfoService.GetWallpaperBase64Cached();

                // Update trackers
                if (vol >= 0) _lastKnownVolume = vol;
                if (bri >= 0) _lastKnownBrightness = bri;
                _lastKnownBattery  = batPct;
                _lastKnownCharging = charging;
                _lastKnownSsid     = ssid;
                _lastKnownMuted    = muted;

                // ── 1) Bundled system_state (works if relay has it in event list) ──
                var state = SystemInfoService.BuildSystemState();
                _vm.WsService.Send(state);

                // ── 2) Individual events (guaranteed to relay — these are in the OLD event list) ──
                _vm.WsService.Send(new { type = "wifi_info",    ssid, strength = sig, connected = wifiConn });
                _vm.WsService.Send(new { type = "battery_info", level = batPct, isCharging = charging });
                _vm.WsService.Send(new { type = "bt_info",      devices = btDevices, bluetoothEnabled = btEnabled });
                _vm.WsService.Send(new { type = "volume",       level = vol });
                _vm.WsService.Send(new { type = "brightness",   level = bri });
                if (!string.IsNullOrEmpty(wallB64))
                    _vm.WsService.Send(new { type = "wallpaper", data = wallB64 });

                Console.WriteLine($"[SendSystemState] Sent all: vol={vol} bri={bri} wifi='{ssid}' bat={batPct}% wall={(!string.IsNullOrEmpty(wallB64) ? "yes" : "no")}");

                // ── 3) Media state ──
                var np = MediaControlService.GetNowPlayingAsync().GetAwaiter().GetResult();
                _vm.WsService.Send(new
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
            catch (Exception ex)
            {
                Console.WriteLine($"[SendSystemState] Error: {ex.Message}");
            }
        }

        /// <summary>
        /// Every 2 seconds: sends individual named events for each piece of system data.
        /// Uses individual events (wifi_info, battery_info, volume, brightness) which are
        /// guaranteed to be relayed by the server, instead of relying on a bundled state_update.
        /// </summary>
        private void BroadcastSystemInfo()
        {
            Task.Run(() =>
            {
                try
                {
                    // Read all current values
                    var currentVol = SystemInfoService.GetVolume();
                    var currentBri = SystemInfoService.GetBrightness();
                    var (ssid, sig, wifiConn) = SystemInfoService.GetWifiInfo();
                    var (batPct, charging, hasBat) = SystemInfoService.GetBatteryInfo();
                    var muted = SystemInfoService.GetMuted();

                    // ── Always send wifi_info and battery_info as individual events ──
                    // These are guaranteed to be relayed (in the server's original event list)
                    if (ssid != _lastKnownSsid || batPct != _lastKnownBattery || charging != _lastKnownCharging || _lastKnownBattery < 0)
                    {
                        _vm.WsService.Send(new { type = "wifi_info",    ssid, strength = sig, connected = wifiConn });
                        _vm.WsService.Send(new { type = "battery_info", level = batPct, isCharging = charging });
                    }

                    // ── Volume: push as individual event when changed ──
                    if (currentVol >= 0 && currentVol != _lastKnownVolume)
                    {
                        _vm.WsService.Send(new { type = "volume", level = currentVol });
                        if (_lastKnownVolume >= 0)
                            Console.WriteLine($"[Broadcast] PC volume changed: {_lastKnownVolume}% → {currentVol}%");
                    }
                    if (currentVol >= 0) _lastKnownVolume = currentVol;

                    // ── Brightness: push as individual event when changed ──
                    if (currentBri >= 0 && currentBri != _lastKnownBrightness)
                    {
                        _vm.WsService.Send(new { type = "brightness", level = currentBri });
                        if (_lastKnownBrightness >= 0)
                            Console.WriteLine($"[Broadcast] PC brightness changed: {_lastKnownBrightness}% → {currentBri}%");
                    }
                    if (currentBri >= 0) _lastKnownBrightness = currentBri;

                    // Update trackers
                    _lastKnownBattery  = batPct;
                    _lastKnownCharging = charging;
                    _lastKnownSsid     = ssid;
                    _lastKnownMuted    = muted;

                    // Also send bundled state_update (for future when server supports it)
                    var update = SystemInfoService.BuildStateUpdate();
                    _vm.WsService.Send(update);

                    // Bluetooth (always send — already in relay list)
                    var btDevices = SystemInfoService.GetBluetoothDevices();
                    var btEnabled = SystemInfoService.GetBluetoothEnabled();
                    _vm.WsService.Send(new { type = "bt_info", devices = btDevices, bluetoothEnabled = btEnabled });

                    // Media now-playing
                    var np = MediaControlService.GetNowPlayingAsync().GetAwaiter().GetResult();
                    _vm.WsService.Send(new
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
                catch (Exception ex)
                {
                    Console.WriteLine($"[BroadcastSystemInfo] Error: {ex.Message}");
                }
            });
        }

        /// <summary>
        /// Wallpaper sync every 5 seconds: always sends the current wallpaper.
        /// Uses hash-based caching internally so only re-encodes when the image file changes.
        /// </summary>
        private void StartWallpaperTimer()
        {
            _wallpaperTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(5) };
            _wallpaperTimer.Tick += (_, _) =>
            {
                if (!_vm.WsService.IsPhoneConnected) return;
                Task.Run(() =>
                {
                    try
                    {
                        var (wallB64, _) = SystemInfoService.GetWallpaperBase64Cached();
                        if (!string.IsNullOrEmpty(wallB64))
                            _vm.WsService.Send(new { type = "wallpaper", data = wallB64 });
                    }
                    catch (Exception ex)
                    {
                        Console.WriteLine($"[WallpaperTimer] Error: {ex.Message}");
                    }
                });
            };
            _wallpaperTimer.Start();
        }

        // ─── Clipboard Watcher (PC → Phone) ───
        private void StartClipboardWatcher()
        {
            _clipboardTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
            _clipboardTimer.Tick += (_, _) =>
            {
                if (!_vm.WsService.IsPhoneConnected) return;
                try
                {
                    if (System.Windows.Clipboard.ContainsText())
                    {
                        var text = System.Windows.Clipboard.GetText();
                        var hash = text.GetHashCode().ToString();
                        if (hash != _lastClipboardHash)
                        {
                            _lastClipboardHash = hash;
                            _vm.WsService.Send(new { type = "clipboard_sync", content = text });
                        }
                    }
                    else if (System.Windows.Clipboard.ContainsImage())
                    {
                        var img = System.Windows.Clipboard.GetImage();
                        if (img != null)
                        {
                            var hash = img.GetHashCode().ToString();
                            if (hash != _lastClipboardHash)
                            {
                                _lastClipboardHash = hash;
                                // Encode image as base64 PNG
                                var encoder = new System.Windows.Media.Imaging.PngBitmapEncoder();
                                encoder.Frames.Add(System.Windows.Media.Imaging.BitmapFrame.Create(img));
                                using var ms = new System.IO.MemoryStream();
                                encoder.Save(ms);
                                var b64 = Convert.ToBase64String(ms.ToArray());
                                _vm.WsService.Send(new { type = "clipboard_sync", content = "[Image]", image = b64 });
                            }
                        }
                    }
                }
                catch { }
            };
            _clipboardTimer.Start();
        }

        // ─── Windows OSD Notification ───
        private System.Windows.Window? _osdWindow;
        private System.Threading.Timer? _osdHideTimer;

        private void ShowWindowsOsd(string icon, string message)
        {
            Dispatcher.Invoke(() =>
            {
                _osdHideTimer?.Dispose();

                if (_osdWindow == null)
                {
                    _osdWindow = new System.Windows.Window
                    {
                        Width           = 220,
                        Height          = 52,
                        WindowStyle     = WindowStyle.None,
                        AllowsTransparency = true,
                        Background      = new SolidColorBrush(Color.FromArgb(220, 15, 15, 25)),
                        Topmost         = true,
                        ShowInTaskbar   = false,
                        ResizeMode      = ResizeMode.NoResize,
                        Left            = 20,
                        Top             = 20,
                    };
                    var border = new Border
                    {
                        CornerRadius = new CornerRadius(14),
                        Background   = new SolidColorBrush(Color.FromArgb(220, 20, 20, 35)),
                        BorderBrush  = new SolidColorBrush(Color.FromArgb(80, 100, 100, 255)),
                        BorderThickness = new Thickness(1),
                    };
                    var sp = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
                    var iconTb = new TextBlock { FontSize = 18, Margin = new Thickness(12, 0, 8, 0), VerticalAlignment = VerticalAlignment.Center };
                    var msgTb  = new TextBlock { FontSize = 14, Foreground = Brushes.White, VerticalAlignment = VerticalAlignment.Center, FontWeight = FontWeights.SemiBold, Margin = new Thickness(0, 0, 12, 0) };
                    sp.Children.Add(iconTb);
                    sp.Children.Add(msgTb);
                    border.Child = sp;
                    _osdWindow.Content = border;
                    // Tag children for update
                    _osdWindow.Tag = (iconTb, msgTb);
                }

                if (_osdWindow.Tag is (TextBlock itb, TextBlock mtb))
                {
                    itb.Text = icon;
                    mtb.Text = message;
                }

                _osdWindow.Show();
                _osdHideTimer = new System.Threading.Timer(_ =>
                    Dispatcher.Invoke(() => _osdWindow?.Hide()), null, 2000, Timeout.Infinite);
            });
        }


        private void GenerateQR()
        {
            if (string.IsNullOrEmpty(_userId) || string.IsNullOrEmpty(_deviceId)) return;
            _vm.GenerateQRCode(_userId, _deviceId, Environment.MachineName);
            QrCodeImage.Source = _vm.QrCodeImage;
        }



        private void SetStatusConnected(bool connected)
        {
            StatusDotBrush.Color = connected
                ? (Color)ColorConverter.ConvertFromString("#3FB950")
                : (Color)ColorConverter.ConvertFromString("#DA3633");
            StatusLabel.Text = connected ? "Connected" : "Waiting for phone...";
        }

        // ─── App Launcher tab ───
        private void PopulateAppsTab()
        {
            AppsPanel.Children.Clear();
            var apps = SystemInfoService.GetInstalledApps();
            foreach (var app in apps)
            {
                var btn = CreateAppButton(app.Name, app.Path);
                AppsPanel.Children.Add(btn);
            }
        }

        private Button CreateAppButton(string name, string path)
        {
            var emoji = name switch
            {
                var n when n.Contains("Chrome") => "🌐",
                var n when n.Contains("Brave")  => "🦁",
                var n when n.Contains("Spotify")=> "🎵",
                var n when n.Contains("VLC")    => "📹",
                var n when n.Contains("Notepad")=> "📝",
                var n when n.Contains("Calc")   => "🔢",
                var n when n.Contains("File")   => "📁",
                var n when n.Contains("Code")   => "💻",
                var n when n.Contains("Task")   => "⚙️",
                var n when n.Contains("Edge")   => "🌐",
                _ => "🪟"
            };

            var btn = new Button
            {
                Width = 100, Height = 100,
                Margin = new Thickness(8),
                Background = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#161B22")),
                BorderBrush = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#30363D")),
                BorderThickness = new Thickness(1),
                Cursor = Cursors.Hand,
                Tag = path
            };

            btn.Template = CreateAppButtonTemplate(emoji, name);
            btn.Click += (s, e) => SystemInfoService.LaunchApp(path);
            return btn;
        }

        private ControlTemplate CreateAppButtonTemplate(string emoji, string name)
        {
            var template = new ControlTemplate(typeof(Button));
            var factory = new FrameworkElementFactory(typeof(Border));
            factory.SetValue(Border.CornerRadiusProperty, new CornerRadius(12));
            factory.SetValue(Border.BackgroundProperty, new TemplateBindingExtension(Button.BackgroundProperty));
            factory.SetValue(Border.BorderBrushProperty, new TemplateBindingExtension(Button.BorderBrushProperty));
            factory.SetValue(Border.BorderThicknessProperty, new TemplateBindingExtension(Button.BorderThicknessProperty));

            var sp = new FrameworkElementFactory(typeof(StackPanel));
            sp.SetValue(StackPanel.HorizontalAlignmentProperty, HorizontalAlignment.Center);
            sp.SetValue(StackPanel.VerticalAlignmentProperty, VerticalAlignment.Center);

            var icon = new FrameworkElementFactory(typeof(TextBlock));
            icon.SetValue(TextBlock.TextProperty, emoji);
            icon.SetValue(TextBlock.FontSizeProperty, 28.0);
            icon.SetValue(TextBlock.HorizontalAlignmentProperty, HorizontalAlignment.Center);

            var label = new FrameworkElementFactory(typeof(TextBlock));
            label.SetValue(TextBlock.TextProperty, name.Length > 12 ? name[..12] + "…" : name);
            label.SetValue(TextBlock.FontSizeProperty, 11.0);
            label.SetValue(TextBlock.ForegroundProperty,
                new SolidColorBrush((Color)ColorConverter.ConvertFromString("#8B949E")));
            label.SetValue(TextBlock.HorizontalAlignmentProperty, HorizontalAlignment.Center);
            label.SetValue(TextBlock.TextWrappingProperty, TextWrapping.Wrap);
            label.SetValue(TextBlock.TextAlignmentProperty, TextAlignment.Center);
            label.SetValue(TextBlock.MarginProperty, new Thickness(0, 6, 0, 0));

            sp.AppendChild(icon);
            sp.AppendChild(label);
            factory.AppendChild(sp);
            template.VisualTree = factory;
            return template;
        }

        private void PopulatePhotos(List<PhotoItem> photos)
        {
            PhotosPanel.Children.Clear();
            AlbumsPanel.Children.Clear();

            // Add placeholder album
            var albumBorder = new Border { Width = 150, Height = 150, Margin = new Thickness(8), CornerRadius = new CornerRadius(12), Background = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#21262D")), Cursor = Cursors.Hand };
            var sp = new StackPanel { VerticalAlignment = VerticalAlignment.Center, HorizontalAlignment = HorizontalAlignment.Center };
            sp.Children.Add(new TextBlock { Text = "📁", FontSize = 48, HorizontalAlignment = HorizontalAlignment.Center });
            sp.Children.Add(new TextBlock { Text = "Camera", Foreground = Brushes.White, FontWeight = FontWeights.SemiBold, Margin = new Thickness(0, 8, 0, 0), HorizontalAlignment = HorizontalAlignment.Center });
            albumBorder.Child = sp;
            AlbumsPanel.Children.Add(albumBorder);

            foreach (var photo in photos)
            {
                var border = new Border
                {
                    Width = 120, Height = 120,
                    Margin = new Thickness(4),
                    CornerRadius = new CornerRadius(8),
                    Background = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#21262D")),
                    Cursor = Cursors.Hand,
                    Tag = photo.Path
                };

                if (photo.ThumbnailBase64 != null)
                {
                    var img = new System.Windows.Controls.Image { Stretch = Stretch.UniformToFill };
                    var bmp = NexLink.Helpers.Helpers.Base64ToBitmapImage(photo.ThumbnailBase64);
                    if (bmp != null) img.Source = bmp;
                    border.Child = img;
                }
                else
                {
                    border.Child = new TextBlock
                    {
                        Text = "🖼️", FontSize = 36,
                        HorizontalAlignment = HorizontalAlignment.Center,
                        VerticalAlignment = VerticalAlignment.Center
                    };
                }
                border.MouseLeftButtonUp += Photo_Click;
                PhotosPanel.Children.Add(border);
            }
        }

        // Dictionary to map photo path → thumbnail border for lazy-load injection
        private readonly Dictionary<string, Border> _photoThumbnailMap = new();

        private void PopulateAlbums(List<PhotoAlbum> albums)
        {
            AlbumsPanel.Children.Clear();
            PhotosPanel.Children.Clear();
            _photoThumbnailMap.Clear();
            AlbumsPanel.Visibility = Visibility.Visible;
            PhotosPanel.Visibility = Visibility.Collapsed;

            foreach (var album in albums)
            {
                var albumCard = CreateAlbumCard(album);
                AlbumsPanel.Children.Add(albumCard);
            }
        }

        private Border CreateAlbumCard(PhotoAlbum album)
        {
            var card = new Border
            {
                Width = 160, Height = 180, Margin = new Thickness(8),
                CornerRadius = new CornerRadius(14),
                Background = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#1A1A2E")),
                BorderBrush = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#30363D")),
                BorderThickness = new Thickness(1),
                Cursor = Cursors.Hand,
                Tag = album
            };

            var icon = album.Name switch
            {
                "Camera"          => "📷",
                "WhatsApp Images" => "💬",
                "Screenshots"     => "🖥️",
                "Download"        => "⬇️",
                _                 => "🖼️"
            };

            var sp = new StackPanel { VerticalAlignment = VerticalAlignment.Center, HorizontalAlignment = HorizontalAlignment.Center };

            // Cover thumbnail or icon
            if (!string.IsNullOrEmpty(album.CoverThumbnail))
            {
                var coverImg = new System.Windows.Controls.Image { Width = 100, Height = 100, Stretch = Stretch.UniformToFill };
                coverImg.Clip = new System.Windows.Media.RectangleGeometry { Rect = new Rect(0, 0, 100, 100), RadiusX = 8, RadiusY = 8 };
                var bmp = NexLink.Helpers.Helpers.Base64ToBitmapImage(album.CoverThumbnail);
                if (bmp != null) coverImg.Source = bmp;
                sp.Children.Add(coverImg);
            }
            else
            {
                sp.Children.Add(new TextBlock { Text = icon, FontSize = 40, HorizontalAlignment = HorizontalAlignment.Center });
            }

            sp.Children.Add(new TextBlock { Text = album.Name, Foreground = Brushes.White, FontWeight = FontWeights.SemiBold, Margin = new Thickness(0, 8, 0, 0), HorizontalAlignment = HorizontalAlignment.Center, TextTrimming = TextTrimming.CharacterEllipsis, MaxWidth = 140 });
            sp.Children.Add(new TextBlock { Text = $"{album.PhotoCount} photos", Foreground = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#8B949E")), FontSize = 11, HorizontalAlignment = HorizontalAlignment.Center });
            card.Child = sp;

            card.MouseLeftButtonUp += (s, e) => ShowAlbumPhotos(album);
            return card;
        }

        private void ShowAlbumPhotos(PhotoAlbum album)
        {
            AlbumsPanel.Visibility = Visibility.Collapsed;
            PhotosPanel.Visibility = Visibility.Visible;
            PhotosPanel.Children.Clear();
            _photoThumbnailMap.Clear();

            // Back button
            var backBtn = new Button
            {
                Content = "← Albums",
                Background = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#161B22")),
                Foreground = Brushes.White,
                BorderThickness = new Thickness(0),
                Padding = new Thickness(12, 6, 12, 6),
                Margin = new Thickness(0, 0, 0, 16),
                Cursor = Cursors.Hand
            };
            backBtn.Click += (s, e) => { AlbumsPanel.Visibility = Visibility.Visible; PhotosPanel.Visibility = Visibility.Collapsed; };
            PhotosPanel.Children.Add(backBtn);

            foreach (var photo in album.Photos)
            {
                var border = new Border
                {
                    Width = 120, Height = 120, Margin = new Thickness(4),
                    CornerRadius = new CornerRadius(8),
                    Background = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#21262D")),
                    Cursor = Cursors.Hand,
                    Tag = photo.Path
                };

                // Placeholder while loading
                var loadingTb = new TextBlock { Text = "🖼️", FontSize = 28, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
                border.Child = loadingTb;

                // Request thumbnail lazily
                _photoThumbnailMap[photo.Path] = border;
                _vm.WsService.Send(new { type = "request_photo_thumbnail", path = photo.Path });

                border.MouseLeftButtonUp += Photo_Click;
                PhotosPanel.Children.Add(border);
            }
        }

        private void ApplyPhotoThumbnail(string path, string thumbnailB64)
        {
            Dispatcher.Invoke(() =>
            {
                if (!_photoThumbnailMap.TryGetValue(path, out var border)) return;
                try
                {
                    var bmp = NexLink.Helpers.Helpers.Base64ToBitmapImage(thumbnailB64);
                    if (bmp == null) return;
                    var img = new System.Windows.Controls.Image { Stretch = Stretch.UniformToFill };
                    img.Source = bmp;
                    border.Child = img;
                }
                catch { }
            });
        }

        private void Photo_Click(object sender, System.Windows.Input.MouseButtonEventArgs e)
        {
            if (sender is Border b && b.Child is System.Windows.Controls.Image img && img.Source != null)
            {
                // Show fullscreen preview in a dialog
                var dlg = new System.Windows.Window
                {
                    Width = 900, Height = 700,
                    Background = new SolidColorBrush(Colors.Black),
                    WindowStyle = WindowStyle.None,
                    AllowsTransparency = true,
                    Topmost = true,
                    Owner = this
                };
                var previewImg = new System.Windows.Controls.Image { Source = img.Source, Stretch = Stretch.Uniform };
                var closeBtn   = new Button { Content = "✕", Foreground = Brushes.White, Background = Brushes.Transparent, BorderThickness = new Thickness(0), FontSize = 24, HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Top, Margin = new Thickness(16), Cursor = Cursors.Hand };
                closeBtn.Click += (_, _) => dlg.Close();
                var grid = new Grid();
                grid.Children.Add(previewImg);
                grid.Children.Add(closeBtn);
                dlg.Content = grid;
                dlg.MouseLeftButtonDown += (_, _) => dlg.DragMove();
                dlg.ShowDialog();
            }
        }

        private void ShowAlbums_Click(object sender, RoutedEventArgs e)
        {
            PhotosPanel.Visibility = Visibility.Collapsed;
            AlbumsPanel.Visibility = Visibility.Visible;
        }

        private void ShowPhotos_Click(object sender, RoutedEventArgs e)
        {
            AlbumsPanel.Visibility = Visibility.Collapsed;
            PhotosPanel.Visibility = Visibility.Visible;
        }

        // ─── Title bar ───
        private void TitleBar_MouseDown(object sender, MouseButtonEventArgs e)
        {
            if (e.LeftButton == MouseButtonState.Pressed) DragMove();
        }
        private void Minimize_Click(object sender, RoutedEventArgs e) => WindowState = WindowState.Minimized;
        private void Close_Click(object sender, RoutedEventArgs e)
        {
            _vm.WsService.Stop();
            Close();
        }

        // ─── Quick actions ───
        private void LockBtn_Click(object sender, RoutedEventArgs e)
        {
            var vm = DataContext as ViewModels.MainViewModel;
            vm?.WsService?.Send(new { type = "lock_phone" });
        }

        private void CameraBtn_Click(object sender, RoutedEventArgs e)
        {
            var vm = DataContext as ViewModels.MainViewModel;
            string lens = (UseFrontCamera.IsChecked == true) ? "front" : "back";
            vm?.WsService?.Send(new { type = "open_camera", lens = lens });
        }
        private void MuteBtn_Click(object sender, RoutedEventArgs e) => SystemInfoService.SetVolume(0);
        private void MusicBtn_Click(object sender, RoutedEventArgs e) => MediaControlService.SendMediaKey("play_pause");

        // ─── Media controls ───
        private void PlayPause_Click(object sender, RoutedEventArgs e) => MediaControlService.SendMediaKey("play_pause");
        private void Prev_Click(object sender, RoutedEventArgs e) => MediaControlService.SendMediaKey("prev");
        private void Next_Click(object sender, RoutedEventArgs e) => MediaControlService.SendMediaKey("next");

        // ─── SMS ───
        private void SmsThread_Selected(object sender, SelectionChangedEventArgs e)
        {
            var thread = SmsThreadList.SelectedItem as SmsThread;
            if (thread == null) return;
            ConversationHeader.Text = thread.ContactName;
            // Show inline messages if we already have them
            if (thread.Messages != null && thread.Messages.Count > 0)
            {
                MessagesList.ItemsSource = thread.Messages;
                MessagesScroll.ScrollToEnd();
            }
            else
            {
                MessagesList.ItemsSource = null;
            }
            // Always fetch fresh from Android
            _vm.WsService.Send(new { type = "get_thread", threadId = thread.Id });
        }

        private void SendSms_Click(object sender, RoutedEventArgs e)
        {
            var thread = SmsThreadList.SelectedItem as SmsThread;
            if (thread == null || string.IsNullOrWhiteSpace(ReplyBox.Text)) return;
            // Send via Android SmsManager
            _vm.WsService.Send(new { type = "sms_send", threadId = thread.Id, body = ReplyBox.Text });
            // Optimistically add to conversation
            var optimistic = new SmsMessage { Id = Guid.NewGuid().ToString(), Body = ReplyBox.Text, Timestamp = DateTime.Now, IsSent = true };
            if (thread.Messages == null) thread.Messages = new List<SmsMessage>();
            thread.Messages.Add(optimistic);
            MessagesList.ItemsSource = null;
            MessagesList.ItemsSource = thread.Messages;
            MessagesScroll.ScrollToEnd();
            ReplyBox.Text = "";
        }

        // ─── Clipboard ───
        private void PullClipboard_Click(object sender, RoutedEventArgs e)
            => _vm.WsService.Send(new { type = "clipboard_pull" });

        private void PushClipboard_Click(object sender, RoutedEventArgs e)
        {
            if (string.IsNullOrWhiteSpace(ClipboardInput.Text)) return;
            _vm.WsService.Send(new { type = "clipboard_push", content = ClipboardInput.Text });
            ClipboardInput.Text = "";
        }

        private void CopyClipItem_Click(object sender, RoutedEventArgs e)
        {
            if (sender is Button btn && btn.Tag is string content)
                System.Windows.Clipboard.SetText(content);
        }

        // ─── Notifications ───
        private void ClearNotifs_Click(object sender, RoutedEventArgs e)
        {
            _vm.Notifications.Clear();
            NotifList.ItemsSource = _vm.Notifications;
            NotifCount.Text = "0";
        }

        // ─── Photos refresh ───
        private void RefreshPhotos_Click(object sender, RoutedEventArgs e)
        {
            _vm.WsService.Send(new { type = "request_photos" });
        }

        // ─── Messages load ───
        private void LoadMessages_Click(object sender, RoutedEventArgs e)
            => _vm.WsService.Send(new { type = "request_mobile_sms" });

        // ─── Ringer mode controls (Windows → Android) ───
        private void RingerSilent_Click(object sender, RoutedEventArgs e)
            => _vm.WsService.Send(new { type = "ringer_mode", mode = 0 });

        private void RingerVibrate_Click(object sender, RoutedEventArgs e)
            => _vm.WsService.Send(new { type = "ringer_mode", mode = 1 });

        private void RingerRing_Click(object sender, RoutedEventArgs e)
            => _vm.WsService.Send(new { type = "ringer_mode", mode = 2 });

        // ─── Remote Volume (Windows → Android) ───
        private void MobileMediaVol_MouseUp(object sender, MouseButtonEventArgs e)
        {
            var slider = sender as Slider;
            if (slider != null)
                _vm.WsService.Send(new { type = "mobile_volume", level = (int)slider.Value });
        }

        private void MobileRingerVol_MouseUp(object sender, MouseButtonEventArgs e)
        {
            var slider = sender as Slider;
            if (slider != null)
                _vm.WsService.Send(new { type = "mobile_ringer_volume", level = (int)slider.Value });
        }

        // ─── Remote Overrides ───
        private void LockBtn_Click(object sender, RoutedEventArgs e)
            => _vm.WsService.Send(new { type = "lock_phone" });

        private void CameraBtn_Click(object sender, RoutedEventArgs e)
        {
            var win = new StreamWindow(_vm, "camera");
            win.Show();
        }

        private void ScreenShareBtn_Click(object sender, RoutedEventArgs e)
        {
            var win = new StreamWindow(_vm, "screen");
            win.Show();
        }
    }

    // Clipboard data item for the list
    public class ClipboardItem
    {
        public string Content { get; set; } = "";
    }
}

