using System;
using System.Collections.Generic;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using NexLink.Models;
using NexLink.Services;
using NexLink.ViewModels;
using Newtonsoft.Json;

namespace NexLink
{
    public partial class MainWindow : Window
    {
        private readonly MainViewModel _vm = new();
        private const int WsPort = 8765;
        private readonly string _sessionToken = Guid.NewGuid().ToString("N")[..8];
        private readonly List<ClipboardItem> _clipboardItems = new();

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
            GenerateQR();
            PopulateAppsTab();
        }

        private async Task InitRelayPairingAsync()
        {
            try
            {
                // Get or create a permanent pairId for this machine
                var pairId = await PairingService.GetOrCreatePairIdAsync("local_user");
                if (!string.IsNullOrEmpty(pairId))
                {
                    _vm.SetPairId(pairId);
                    // Also connect to relay as desktop so phone can reach us cross-network
                    _vm.WsService.StartRelay(pairId);
                    StatusBar.Text = $"Relay ready • PairID: {pairId[..8]}…";
                }
            }
            catch (Exception ex)
            {
                StatusBar.Text = $"Relay unavailable (offline mode): {ex.Message}";
            }
        }

        private void StartServer()
        {
            try
            {
                _vm.WsService.Start(WsPort);
                PortLabel.Text = $"ws://0.0.0.0:{WsPort}";

                _vm.WsService.PhoneConnected += () => Dispatcher.Invoke(() =>
                {
                    DisconnectedOverlay.Visibility = Visibility.Collapsed;
                    ConnectedDashboard.Visibility = Visibility.Visible;
                    PhoneInfoPanel.Visibility = Visibility.Visible;
                    PhoneNameLabel.Text = _vm.PhoneName;
                    SetStatusConnected(true);
                    StatusBar.Text = "Phone connected";
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
                            var lvl     = msg["level"]?.ToObject<int>() ?? 0;
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
                            var content = msg["content"]?.ToString() ?? "";
                            if (!string.IsNullOrEmpty(content))
                            {
                                _clipboardItems.Insert(0, new ClipboardItem { Content = content });
                                ClipboardList.ItemsSource = null;
                                ClipboardList.ItemsSource = _clipboardItems;
                            }
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

        private void GenerateQR()
        {
            var allIps = WebSocketService.GetAllLocalIPs();
            var ip = WebSocketService.GetLocalIPAddress();

            // Populate the IP selector
            IpSelector.ItemsSource = allIps;
            if (allIps.Contains(ip))
                IpSelector.SelectedItem = ip;
            else if (allIps.Count > 0)
            {
                IpSelector.SelectedIndex = 0;
                ip = allIps[0];
            }

            IpPortLabel.Text = $"Port: {WsPort}";
            _vm.GenerateQRCode(ip, WsPort, Environment.MachineName, _sessionToken);
            QrCodeImage.Source = _vm.QrCodeImage;
        }

        private void IpSelector_SelectionChanged(object sender, SelectionChangedEventArgs e)
        {
            if (IpSelector.SelectedItem is string selectedIp)
            {
                IpPortLabel.Text = $"Port: {WsPort}";
                _vm.GenerateQRCode(selectedIp, WsPort, Environment.MachineName, _sessionToken);
                QrCodeImage.Source = _vm.QrCodeImage;
                StatusBar.Text = $"QR updated for {selectedIp}:{WsPort}";
            }
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
