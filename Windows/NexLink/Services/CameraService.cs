using System;
using System.IO;
using System.Threading;
using System.Threading.Tasks;
using OpenCvSharp;

namespace NexLink.Services
{
    public class CameraService
    {
        private CancellationTokenSource? _cts;
        private VideoCapture? _capture;

        public void StartStreaming(Action<string> onFrame, int intervalMs = 100)
        {
            if (_cts != null && !_cts.IsCancellationRequested) return; // Already streaming
            
            _cts = new CancellationTokenSource();
            var token = _cts.Token;
            Task.Run(() =>
            {
                _capture = new VideoCapture(0);
                if (!_capture.IsOpened()) return;

                using var frame = new Mat();
                while (!token.IsCancellationRequested)
                {
                    _capture.Read(frame);
                    if (frame.Empty()) continue;
                    var resized = frame.Resize(new Size(640, 480));
                    Cv2.ImEncode(".jpg", resized, out var buf,
                        new ImageEncodingParam(ImwriteFlags.JpegQuality, 50));
                    onFrame(Convert.ToBase64String(buf));
                    Thread.Sleep(intervalMs);
                }
                _capture.Release();
            }, token);
        }

        public void StopStreaming()
        {
            _cts?.Cancel();
            _capture?.Release();
            _capture?.Dispose();
            _capture = null;
            _cts?.Dispose();
            _cts = null; // Reset so StartStreaming can be called again
        }
    }
}
