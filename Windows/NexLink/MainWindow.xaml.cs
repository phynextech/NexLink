using System;
using System.Collections.Generic;
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

        // Clipboard monitoring
        private string _lastClipboardHash = "";
        private DispatcherTimer? _clipboardTimer;

        // Volume/brightness tracking — reserved for future delta detection

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
                            var threads = msg["threads"]?.ToObject<List<SmsThread>>();
                            if (threads != null)
                            {
                                _vm.SmsThreads.Clear();
                                foreach (var t in threads) _vm.SmsThreads.Add(t);
                                SmsThreadList.ItemsSource = _vm.SmsThreads;
                            }
                            break;

                        case "photo_list":
                            var photos = msg["photos"]?.ToObject<List<PhotoItem>>();
                            if (photos != null) PopulatePhotos(photos);
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

                        case "volume":
                            var newVol = msg["level"]?.ToObject<int>() ?? -1;
                            if (newVol >= 0)
                            {
                                SystemInfoService.SetVolume(newVol);
                                ShowWindowsOsd("🔊", $"Volume: {newVol}%");
                                // Confirm back so Android slider stays in sync
                                _vm.WsService.Send(new { type = "volume_ack", level = newVol });
                            }
                            break;

                        case "brightness":
                            var newBri = msg["level"]?.ToObject<int>() ?? -1;
                            if (newBri >= 0)
                            {
                                SystemInfoService.SetBrightness(newBri);
                                ShowWindowsOsd("☀", $"Brightness: {newBri}%");
                                // Confirm back so Android slider stays in sync
                                _vm.WsService.Send(new { type = "brightness_ack", level = newBri });
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
                                    var (ssid, sig) = SystemInfoService.GetWifiInfo();
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
            _broadcastTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(2) };
            _broadcastTimer.Tick += (_, _) =>
            {
                if (_vm.WsService.IsPhoneConnected)
                    BroadcastSystemInfo();
            };
            _broadcastTimer.Start();
        }

        /// <summary>
        /// Sends a FULL system_state to the phone (all real values, including wallpaper).
        /// Called once immediately on connect and on request_info.
        /// </summary>
        private void SendSystemState()
        {
            try
            {
                // Build full snapshot (reads wallpaper, all hardware values)
                var state = SystemInfoService.BuildSystemState();
                _vm.WsService.Send(state);

                // Also push media state
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
        /// Sends a lightweight state_update every 2 seconds (no wallpaper, no media art).
        /// Keeps sliders and status chips in sync.
        /// </summary>
        private void BroadcastSystemInfo()
        {
            Task.Run(() =>
            {
                try
                {
                    // Lightweight: volume, brightness, wifi, battery, mute
                    var update = SystemInfoService.BuildStateUpdate();
                    _vm.WsService.Send(update);

                    // Bluetooth (less frequent, but still useful live)
                    var btDevices = SystemInfoService.GetBluetoothDevices();
                    var btEnabled = SystemInfoService.GetBluetoothEnabled();
                    _vm.WsService.Send(new { type = "bt_info", devices = btDevices, bluetoothEnabled = btEnabled });

                    // Wallpaper (only if it changed, checked via hash)
                    var (wallB64, wallChanged) = SystemInfoService.GetWallpaperBase64Cached();
                    if (wallChanged && !string.IsNullOrEmpty(wallB64))
                        _vm.WsService.Send(new { type = "wallpaper", data = wallB64 });

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
                        Left            = SystemParameters.WorkArea.Width - 240,
                        Top             = SystemParameters.WorkArea.Height - 80,
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

        // ─── Photos ───
        private void PopulatePhotos(List<PhotoItem> photos)
        {
            PhotosPanel.Children.Clear();
            foreach (var photo in photos)
            {
                var border = new Border
                {
                    Width = 120, Height = 120,
                    Margin = new Thickness(4),
                    CornerRadius = new CornerRadius(8),
                    Background = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#21262D")),
                    Cursor = Cursors.Hand
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
                PhotosPanel.Children.Add(border);
            }
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
        private void LockBtn_Click(object sender, RoutedEventArgs e) => SystemInfoService.LockPC();
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
            MessagesList.ItemsSource = thread.Messages;
            MessagesScroll.ScrollToEnd();
            _vm.WsService.Send(new { type = "get_thread", threadId = thread.Id });
        }

        private void SendSms_Click(object sender, RoutedEventArgs e)
        {
            var thread = SmsThreadList.SelectedItem as SmsThread;
            if (thread == null || string.IsNullOrWhiteSpace(ReplyBox.Text)) return;
            _vm.WsService.Send(new { type = "sms_send", threadId = thread.Id, body = ReplyBox.Text });
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
            => _vm.WsService.Send(new { type = "request_photos" });
    }

    // Clipboard data item for the list
    public class ClipboardItem
    {
        public string Content { get; set; } = "";
    }
}
