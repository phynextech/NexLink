using System;
using System.Globalization;
using System.IO;
using System.Windows;
using System.Windows.Data;
using System.Windows.Media.Imaging;

namespace NexLink.Helpers
{
    /// <summary>Converts bool → HorizontalAlignment (true=Right for sent messages, false=Left for received)</summary>
    public class BoolToAlignmentConverter : IValueConverter
    {
        public object Convert(object value, Type targetType, object parameter, CultureInfo culture)
            => value is true ? HorizontalAlignment.Right : HorizontalAlignment.Left;
        public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture)
            => throw new NotImplementedException();
    }

    /// <summary>Converts bool → bubble color string (true=BluePrimary, false=card)</summary>
    public class BoolToColorConverter : IValueConverter
    {
        public object Convert(object value, Type targetType, object parameter, CultureInfo culture)
            => value is true ? "#378ADD" : "#21262D";
        public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture)
            => throw new NotImplementedException();
    }

    /// <summary>Converts int/string → Visibility (0/empty=Collapsed, else Visible)</summary>
    public class NonZeroToVisibilityConverter : IValueConverter
    {
        public object Convert(object value, Type targetType, object parameter, CultureInfo culture)
        {
            if (value is int i && i > 0) return Visibility.Visible;
            if (value is string s && !string.IsNullOrEmpty(s)) return Visibility.Visible;
            return Visibility.Collapsed;
        }
        public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture)
            => throw new NotImplementedException();
    }

    /// <summary>Converts bool → Visibility</summary>
    public class BoolToVisibilityConverter : IValueConverter
    {
        public object Convert(object value, Type targetType, object parameter, CultureInfo culture)
            => value is true ? Visibility.Visible : Visibility.Collapsed;
        public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture)
            => value is Visibility.Visible;
    }

    /// <summary>Converts bool → Visibility (inverted: false=Visible)</summary>
    public class InverseBoolToVisibilityConverter : IValueConverter
    {
        public object Convert(object value, Type targetType, object parameter, CultureInfo culture)
            => value is true ? Visibility.Collapsed : Visibility.Visible;
        public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture)
            => value is Visibility.Collapsed;
    }

    /// <summary>Converts bool → Thickness for chat bubble margin (sent=right, received=left)</summary>
    public class ChatBubbleMarginConverter : IValueConverter
    {
        public object Convert(object value, Type targetType, object parameter, CultureInfo culture)
            => value is true ? new Thickness(60, 2, 12, 2) : new Thickness(12, 2, 60, 2);
        public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture)
            => throw new NotImplementedException();
    }

    /// <summary>TransferState.Transferring → Visible</summary>
    public class IsTransferringConverter : IValueConverter
    {
        public object Convert(object value, Type targetType, object parameter, CultureInfo culture)
            => value is NexLink.Models.TransferState s && s == NexLink.Models.TransferState.Transferring
               ? Visibility.Visible : Visibility.Collapsed;
        public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture)
            => throw new NotImplementedException();
    }

    /// <summary>TransferState.Complete → Visible</summary>
    public class IsCompleteConverter : IValueConverter
    {
        public object Convert(object value, Type targetType, object parameter, CultureInfo culture)
            => value is NexLink.Models.TransferState s && s == NexLink.Models.TransferState.Complete
               ? Visibility.Visible : Visibility.Collapsed;
        public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture)
            => throw new NotImplementedException();
    }

    /// <summary>string? path → Visible if file exists on disk</summary>
    public class HasLocalFileConverter : IValueConverter
    {
        public object Convert(object value, Type targetType, object parameter, CultureInfo culture)
            => value is string p && File.Exists(p) ? Visibility.Visible : Visibility.Collapsed;
        public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture)
            => throw new NotImplementedException();
    }

    /// <summary>Utility helpers</summary>
    public static class Helpers
    {
        public static BitmapImage? Base64ToBitmapImage(string base64)
        {
            try
            {
                var bytes = System.Convert.FromBase64String(base64);
                var bi = new BitmapImage();
                using var ms = new MemoryStream(bytes);
                bi.BeginInit();
                bi.StreamSource = ms;
                bi.CacheOption = BitmapCacheOption.OnLoad;
                bi.EndInit();
                bi.Freeze();
                return bi;
            }
            catch { return null; }
        }

        public static string FormatFileSize(long bytes)
        {
            if (bytes < 1024) return $"{bytes} B";
            if (bytes < 1024 * 1024) return $"{bytes / 1024} KB";
            if (bytes < 1024L * 1024 * 1024) return $"{bytes / (1024 * 1024)} MB";
            return $"{bytes / (1024L * 1024 * 1024)} GB";
        }

        public static string TimeAgo(DateTime dt)
        {
            var diff = DateTime.Now - dt;
            if (diff.TotalMinutes < 1) return "just now";
            if (diff.TotalHours < 1)   return $"{(int)diff.TotalMinutes}m ago";
            if (diff.TotalDays < 1)    return $"{(int)diff.TotalHours}h ago";
            return dt.ToString("MMM d");
        }
    }
}
