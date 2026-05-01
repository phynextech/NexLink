using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;
using System.Threading.Tasks;

namespace NexLink.Services
{
    public class ScreenCaptureService
    {
        private CancellationTokenSource? _cts;

        [DllImport("gdi32.dll")] static extern bool BitBlt(IntPtr hdcDest, int nXDest, int nYDest, int nWidth, int nHeight, IntPtr hdcSrc, int nXSrc, int nYSrc, uint dwRop);
        [DllImport("user32.dll")] static extern IntPtr GetDesktopWindow();
        [DllImport("user32.dll")] static extern IntPtr GetWindowDC(IntPtr hWnd);
        [DllImport("gdi32.dll")] static extern IntPtr CreateCompatibleDC(IntPtr hDC);
        [DllImport("gdi32.dll")] static extern IntPtr CreateCompatibleBitmap(IntPtr hDC, int nWidth, int nHeight);
        [DllImport("gdi32.dll")] static extern IntPtr SelectObject(IntPtr hDC, IntPtr hObject);
        [DllImport("gdi32.dll")] static extern bool DeleteObject(IntPtr hObject);
        [DllImport("gdi32.dll")] static extern bool DeleteDC(IntPtr hDC);
        [DllImport("user32.dll")] static extern bool ReleaseDC(IntPtr hWnd, IntPtr hDC);

        const uint SRCCOPY = 0xCC0020;

        public void StartStreaming(Action<string> onFrame, int intervalMs = 500)
        {
            if (_cts != null && !_cts.IsCancellationRequested) return; // Already streaming
            
            _cts = new CancellationTokenSource();
            var token = _cts.Token;
            Task.Run(async () =>
            {
                while (!token.IsCancellationRequested)
                {
                    try
                    {
                        var b64 = CaptureScreen();
                        onFrame(b64);
                    }
                    catch { }
                    await Task.Delay(intervalMs, token);
                }
            }, token);
        }

        public void StopStreaming()
        {
            _cts?.Cancel();
            _cts?.Dispose();
            _cts = null; // Reset so StartStreaming can be called again
        }

        public string CaptureScreen()
        {
            var screen = System.Windows.Forms.Screen.PrimaryScreen!;
            int w = screen.Bounds.Width, h = screen.Bounds.Height;
            var bmp = new Bitmap(w, h, System.Drawing.Imaging.PixelFormat.Format32bppArgb);

            using var g = Graphics.FromImage(bmp);
            g.CopyFromScreen(0, 0, 0, 0, bmp.Size);

            // Scale down to reduce bandwidth (720p max)
            var scaled = ScaleBitmap(bmp, 1280, 720);
            using var ms = new MemoryStream();
            var encoder = GetJpegEncoder();
            var encoderParams = new EncoderParameters(1);
            encoderParams.Param[0] = new EncoderParameter(Encoder.Quality, 60L);
            scaled.Save(ms, encoder, encoderParams);
            return Convert.ToBase64String(ms.ToArray());
        }

        private Bitmap ScaleBitmap(Bitmap src, int maxW, int maxH)
        {
            float scaleW = (float)maxW / src.Width;
            float scaleH = (float)maxH / src.Height;
            float scale = Math.Min(scaleW, scaleH);
            if (scale >= 1f) return src;
            int newW = (int)(src.Width * scale), newH = (int)(src.Height * scale);
            var dst = new Bitmap(newW, newH);
            using var g = Graphics.FromImage(dst);
            g.InterpolationMode = System.Drawing.Drawing2D.InterpolationMode.Bilinear;
            g.DrawImage(src, 0, 0, newW, newH);
            return dst;
        }

        private static ImageCodecInfo GetJpegEncoder()
        {
            foreach (var codec in ImageCodecInfo.GetImageEncoders())
                if (codec.FormatID == ImageFormat.Jpeg.Guid) return codec;
            return ImageCodecInfo.GetImageEncoders()[0];
        }

        // Get wallpaper as base64
        public static string GetWallpaperBase64()
        {
            try
            {
                string transcodedPath = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), 
                    @"Microsoft\Windows\Themes\TranscodedWallpaper");
                
                string wallpaperPath = File.Exists(transcodedPath) ? transcodedPath : 
                    Registry.GetValue(@"HKEY_CURRENT_USER\Control Panel\Desktop", "Wallpaper", "") as string ?? "";
                    
                if (string.IsNullOrEmpty(wallpaperPath) || !File.Exists(wallpaperPath))
                    return "";
                    
                var bmp = new Bitmap(wallpaperPath);
                var scaled = new Bitmap(800, 450);
                using var g = Graphics.FromImage(scaled);
                g.DrawImage(bmp, 0, 0, 800, 450);
                using var ms = new MemoryStream();
                scaled.Save(ms, ImageFormat.Jpeg);
                return Convert.ToBase64String(ms.ToArray());
            }
            catch { return ""; }
        }
    }

    // Minimal registry access wrapper (avoids Microsoft.Win32 namespace conflict)
    internal static class Registry
    {
        public static object? GetValue(string keyName, string valueName, object? defaultValue)
            => Microsoft.Win32.Registry.GetValue(keyName, valueName, defaultValue);
    }
}
