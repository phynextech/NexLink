using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;
using System.Threading.Tasks;
using Windows.Media.Control;

namespace NexLink.Services
{
    public class MediaControlService
    {
        [DllImport("user32.dll")]
        static extern void keybd_event(byte bVk, byte bScan, uint dwFlags, int dwExtraInfo);

        const byte VK_MEDIA_PLAY_PAUSE = 0xB3;
        const byte VK_MEDIA_NEXT_TRACK = 0xB0;
        const byte VK_MEDIA_PREV_TRACK = 0xB1;
        const byte VK_MEDIA_STOP       = 0xB2;
        const uint KEYEVENTF_EXTENDEDKEY = 0x0001;
        const uint KEYEVENTF_KEYUP       = 0x0002;

        public static void SendMediaKey(string action)
        {
            if (action == "play_pause")
            {
                // Try SMTC first, it is much more reliable
                Task.Run(async () => {
                    try {
                        var smgr = await GlobalSystemMediaTransportControlsSessionManager.RequestAsync();
                        var session = smgr.GetCurrentSession();
                        if (session != null) {
                            await session.TryTogglePlayPauseAsync();
                            return;
                        }
                    } catch { }
                    // Fallback to key
                    SendKeycode(VK_MEDIA_PLAY_PAUSE);
                });
                return;
            }

            byte key = action switch
            {
                "next" => VK_MEDIA_NEXT_TRACK,
                "prev" => VK_MEDIA_PREV_TRACK,
                "stop" => VK_MEDIA_STOP,
                _ => VK_MEDIA_PLAY_PAUSE
            };
            SendKeycode(key);
        }

        private static void SendKeycode(byte key)
        {
            keybd_event(key, 0, KEYEVENTF_EXTENDEDKEY, 0);
            Thread.Sleep(50);
            keybd_event(key, 0, KEYEVENTF_EXTENDEDKEY | KEYEVENTF_KEYUP, 0);
        }

        // ─── Now Playing via SMTC ────────────────────────────────────────────
        public static async Task<NowPlayingResult> GetNowPlayingAsync()
        {
            try
            {
                var sessionManager = await GlobalSystemMediaTransportControlsSessionManager.RequestAsync();
                var session = sessionManager.GetCurrentSession();
                if (session == null)
                    return new NowPlayingResult { Title = "Nothing Playing" };

                var info = await session.TryGetMediaPropertiesAsync();
                var pb   = session.GetPlaybackInfo();
                var tl   = session.GetTimelineProperties();

                // Get app source name (e.g. "Spotify", "YouTube (chrome)")
                string appSource = "";
                try
                {
                    var sourceApp = session.SourceAppUserModelId ?? "";
                    // Map known app IDs to friendly names
                    if (sourceApp.Contains("Spotify",    StringComparison.OrdinalIgnoreCase)) appSource = "Spotify";
                    else if (sourceApp.Contains("chrome", StringComparison.OrdinalIgnoreCase)) appSource = "YouTube (Chrome)";
                    else if (sourceApp.Contains("brave",  StringComparison.OrdinalIgnoreCase)) appSource = "YouTube (Brave)";
                    else if (sourceApp.Contains("msedge", StringComparison.OrdinalIgnoreCase)) appSource = "YouTube (Edge)";
                    else if (sourceApp.Contains("vlc",    StringComparison.OrdinalIgnoreCase)) appSource = "VLC";
                    else if (sourceApp.Contains("groove", StringComparison.OrdinalIgnoreCase)) appSource = "Groove Music";
                    else if (!string.IsNullOrEmpty(sourceApp))
                    {
                        // Use last part of app ID as fallback
                        var parts = sourceApp.Split(new[] { '!', '.', '_' }, StringSplitOptions.RemoveEmptyEntries);
                        appSource = parts.Length > 0 ? parts[^1] : sourceApp;
                    }
                }
                catch { }

                // Album art
                string? albumArtB64 = null;
                if (info.Thumbnail != null)
                {
                    try
                    {
                        using var stream = await info.Thumbnail.OpenReadAsync();
                        using var ms     = new MemoryStream();
                        await stream.AsStream().CopyToAsync(ms);
                        albumArtB64 = Convert.ToBase64String(ms.ToArray());
                    }
                    catch { }
                }

                bool isPlaying = pb.PlaybackStatus == GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing;

                // Shuffle/Repeat
                bool shuffleActive = pb.IsShuffleActive ?? false;
                int  repeatMode    = pb.AutoRepeatMode switch
                {
                    Windows.Media.MediaPlaybackAutoRepeatMode.None  => 0,
                    Windows.Media.MediaPlaybackAutoRepeatMode.List  => 1,
                    Windows.Media.MediaPlaybackAutoRepeatMode.Track => 2,
                    _ => 0
                };

                return new NowPlayingResult
                {
                    Title          = info.Title ?? "Unknown",
                    Artist         = info.Artist ?? "Unknown",
                    AlbumArtBase64 = albumArtB64,
                    IsPlaying      = isPlaying,
                    PositionSec    = tl.Position.TotalSeconds,
                    DurationSec    = tl.EndTime.TotalSeconds,
                    AppSource      = appSource,
                    ShuffleActive  = shuffleActive,
                    RepeatMode     = repeatMode,
                };
            }
            catch
            {
                return new NowPlayingResult { Title = "Not Playing" };
            }
        }

        public static async Task SetPlaybackPositionAsync(double positionSec)
        {
            try
            {
                var sessionManager = await GlobalSystemMediaTransportControlsSessionManager.RequestAsync();
                var session = sessionManager.GetCurrentSession();
                if (session != null)
                    await session.TryChangePlaybackPositionAsync((long)(positionSec * 10_000_000));
            }
            catch { }
        }

        public static async Task ToggleShuffleAsync()
        {
            try
            {
                var sessionManager = await GlobalSystemMediaTransportControlsSessionManager.RequestAsync();
                var session = sessionManager.GetCurrentSession();
                if (session != null)
                {
                    var pb = session.GetPlaybackInfo();
                    bool current = pb.IsShuffleActive ?? false;
                    await session.TryChangeShuffleActiveAsync(!current);
                }
            }
            catch { SendMediaKey("play_pause"); } // graceful fallback
        }

        public static async Task ToggleRepeatAsync()
        {
            try
            {
                var sessionManager = await GlobalSystemMediaTransportControlsSessionManager.RequestAsync();
                var session = sessionManager.GetCurrentSession();
                if (session != null)
                {
                    var pb = session.GetPlaybackInfo();
                    var next = pb.AutoRepeatMode switch
                    {
                        Windows.Media.MediaPlaybackAutoRepeatMode.None  => Windows.Media.MediaPlaybackAutoRepeatMode.List,
                        Windows.Media.MediaPlaybackAutoRepeatMode.List  => Windows.Media.MediaPlaybackAutoRepeatMode.Track,
                        Windows.Media.MediaPlaybackAutoRepeatMode.Track => Windows.Media.MediaPlaybackAutoRepeatMode.None,
                        _ => Windows.Media.MediaPlaybackAutoRepeatMode.List
                    };
                    await session.TryChangeAutoRepeatModeAsync(next);
                }
            }
            catch { }
        }
    }

    public class NowPlayingResult
    {
        public string  Title          { get; set; } = "";
        public string  Artist         { get; set; } = "";
        public string? AlbumArtBase64 { get; set; }
        public bool    IsPlaying      { get; set; }
        public double  PositionSec    { get; set; }
        public double  DurationSec    { get; set; }
        public string  AppSource      { get; set; } = "";
        public bool    ShuffleActive  { get; set; }
        public int     RepeatMode     { get; set; }
    }
}
