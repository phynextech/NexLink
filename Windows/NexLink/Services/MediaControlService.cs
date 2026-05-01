using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;
using System.Threading.Tasks;

namespace NexLink.Services
{
    public class MediaControlService
    {
        // Windows SMTC via GlobalSystemMediaTransportControlsSessionManager
        // Fallback: SendInput with media keys

        [DllImport("user32.dll")]
        static extern void keybd_event(byte bVk, byte bScan, uint dwFlags, int dwExtraInfo);

        const byte VK_MEDIA_PLAY_PAUSE = 0xB3;
        const byte VK_MEDIA_NEXT_TRACK = 0xB0;
        const byte VK_MEDIA_PREV_TRACK = 0xB1;
        const byte VK_MEDIA_STOP = 0xB2;
        const uint KEYEVENTF_EXTENDEDKEY = 0x0001;
        const uint KEYEVENTF_KEYUP = 0x0002;

        public static void SendMediaKey(string action)
        {
            byte key = action switch
            {
                "play_pause" => VK_MEDIA_PLAY_PAUSE,
                "next"       => VK_MEDIA_NEXT_TRACK,
                "prev"       => VK_MEDIA_PREV_TRACK,
                "stop"       => VK_MEDIA_STOP,
                _            => VK_MEDIA_PLAY_PAUSE
            };
            keybd_event(key, 0, KEYEVENTF_EXTENDEDKEY, 0);
            Thread.Sleep(50);
            keybd_event(key, 0, KEYEVENTF_EXTENDEDKEY | KEYEVENTF_KEYUP, 0);
        }

        // Get currently playing media via SMTC
        public static async Task<(string title, string artist, string? albumArtBase64, bool isPlaying)> GetNowPlayingAsync()
        {
            try
            {
                var sessionManager = await Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager.RequestAsync();
                var session = sessionManager.GetCurrentSession();
                if (session == null) return ("Nothing Playing", "", null, false);

                var info = await session.TryGetMediaPropertiesAsync();
                var pb = session.GetPlaybackInfo();
                string? albumArtB64 = null;

                if (info.Thumbnail != null)
                {
                    using var stream = await info.Thumbnail.OpenReadAsync();
                    using var ms = new MemoryStream();
                    await stream.AsStream().CopyToAsync(ms);
                    albumArtB64 = Convert.ToBase64String(ms.ToArray());
                }

                bool isPlaying = pb.PlaybackStatus ==
                    Windows.Media.Control.GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing;

                return (info.Title ?? "Unknown", info.Artist ?? "Unknown", albumArtB64, isPlaying);
            }
            catch
            {
                return ("Not Playing", "", null, false);
            }
        }
    }
}
