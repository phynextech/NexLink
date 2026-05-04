using System;
using System.IO;
using System.Windows;
using System.Windows.Media.Imaging;
using NexLink.ViewModels;

namespace NexLink
{
    public partial class StreamWindow : Window
    {
        private MainViewModel _vm;
        private string _streamType; // "camera" or "screen"
        private bool _isFrontCamera = false;

        public StreamWindow(MainViewModel vm, string streamType)
        {
            InitializeComponent();
            _vm = vm;
            _streamType = streamType;
            HeaderTxt.Text = streamType == "camera" ? "Mobile Camera Stream" : "Mobile Screen Stream";
            
            if (streamType == "camera")
            {
                SwitchCamBtn.Visibility = Visibility.Visible;
            }

            // Register for incoming frames
            _vm.WsService.MessageReceived += WsService_MessageReceived;
            this.Closed += StreamWindow_Closed;
        }

        private void WsService_MessageReceived(Newtonsoft.Json.Linq.JObject msg)
        {
            var type = msg["type"]?.ToString();
            if (type == "mobile_camera_frame" && _streamType == "camera")
            {
                UpdateFrame(msg["data"]?.ToString());
            }
            else if (type == "mobile_screen_frame" && _streamType == "screen")
            {
                UpdateFrame(msg["data"]?.ToString());
            }
        }

        private void UpdateFrame(string base64Data)
        {
            if (string.IsNullOrEmpty(base64Data)) return;
            Dispatcher.InvokeAsync(() =>
            {
                try
                {
                    var bytes = Convert.FromBase64String(base64Data);
                    using var ms = new MemoryStream(bytes);
                    var bmp = new BitmapImage();
                    bmp.BeginInit();
                    bmp.StreamSource = ms;
                    bmp.CacheOption = BitmapCacheOption.OnLoad;
                    bmp.EndInit();
                    StreamImage.Source = bmp;
                }
                catch { }
            });
        }

        private void StartBtn_Click(object sender, RoutedEventArgs e)
        {
            if (_streamType == "camera")
                _vm.WsService.Send(new { type = "start_mobile_camera", front = _isFrontCamera });
            else
                _vm.WsService.Send(new { type = "start_mobile_screen" });
        }

        private void SwitchCamBtn_Click(object sender, RoutedEventArgs e)
        {
            _isFrontCamera = !_isFrontCamera;
            _vm.WsService.Send(new { type = "start_mobile_camera", front = _isFrontCamera });
        }

        private void StopBtn_Click(object sender, RoutedEventArgs e)
        {
            if (_streamType == "camera")
                _vm.WsService.Send(new { type = "stop_mobile_camera" });
            else
                _vm.WsService.Send(new { type = "stop_mobile_screen" });
            StreamImage.Source = null;
        }

        private void StreamWindow_Closed(object sender, EventArgs e)
        {
            StopBtn_Click(null, null);
            _vm.WsService.MessageReceived -= WsService_MessageReceived;
        }
    }
}
