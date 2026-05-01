using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Threading.Tasks;
using System.Windows.Media.Imaging;
using QRCoder;

namespace NexLink.Services
{
    public static class QRCodeService
    {
        public static BitmapImage GenerateQRCode(string payload, int pixelSize = 10)
        {
            using var qrGenerator = new QRCodeGenerator();
            var qrData = qrGenerator.CreateQrCode(payload, QRCodeGenerator.ECCLevel.Q);
            using var qrCode = new QRCode(qrData);
            var bmp = qrCode.GetGraphic(pixelSize, 
                Color.FromArgb(0xE6, 0xED, 0xF3),  // TextPrimary
                Color.FromArgb(0x16, 0x1B, 0x22),  // BackgroundCard
                true); // drawQuietZones

            return BitmapToBitmapImage(bmp);
        }

        private static BitmapImage BitmapToBitmapImage(Bitmap bmp)
        {
            using var ms = new MemoryStream();
            bmp.Save(ms, ImageFormat.Png);
            ms.Position = 0;
            var bi = new BitmapImage();
            bi.BeginInit();
            bi.StreamSource = ms;
            bi.CacheOption = BitmapCacheOption.OnLoad;
            bi.EndInit();
            bi.Freeze();
            return bi;
        }
    }
}
