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
        
        [DllImport("user32.dll")]
        static extern bool GetWindowRect(IntPtr hWnd, out RECT lpRect);

        [DllImport("user32.dll")]
        static extern bool EnumWindows(EnumWindowsProc lpEnumFunc, IntPtr lParam);
        public delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);

        [DllImport("user32.dll")]
        static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint lpdwProcessId);

        [DllImport("user32.dll", CharSet = CharSet.Auto)]
        static extern int GetWindowTextLength(IntPtr hWnd);

        [DllImport("user32.dll", CharSet = CharSet.Auto)]
        static extern int GetWindowText(IntPtr hWnd, System.Text.StringBuilder lpString, int nMaxCount);

        [DllImport("user32.dll")]
        static extern bool IsWindowVisible(IntPtr hWnd);

        [StructLayout(LayoutKind.Sequential)]
        public struct RECT
        {
            public int Left;
            public int Top;
            public int Right;
            public int Bottom;
            public int Width => Right - Left;
            public int Height => Bottom - Top;
        }

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

                // If no album art is provided (e.g. YouTube in browser), capture the media player window!
                if (albumArtB64 == null)
                {
                    try
                    {
                        var bounds = await GetActiveMediaWindowBoundsAsync();
                        if (bounds != null)
                        {
                            albumArtB64 = new ScreenCaptureService().CaptureScreen(bounds, highQuality: true);
                        }
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

        public static async Task<RECT?> GetActiveMediaWindowBoundsAsync()
        {
            try
            {
                var smgr = await GlobalSystemMediaTransportControlsSessionManager.RequestAsync();
                var session = smgr.GetCurrentSession();
                if (session == null) return null;

                string appId = session.SourceAppUserModelId ?? "";
                if (string.IsNullOrEmpty(appId)) return null;

                // Match typical media app process names based on appId
                string targetProcName = "";
                if (appId.Contains("Spotify", StringComparison.OrdinalIgnoreCase)) targetProcName = "Spotify";
                else if (appId.Contains("chrome", StringComparison.OrdinalIgnoreCase)) targetProcName = "chrome";
                else if (appId.Contains("brave", StringComparison.OrdinalIgnoreCase)) targetProcName = "brave";
                else if (appId.Contains("msedge", StringComparison.OrdinalIgnoreCase)) targetProcName = "msedge";
                else if (appId.Contains("vlc", StringComparison.OrdinalIgnoreCase)) targetProcName = "vlc";
                else if (appId.Contains("firefox", StringComparison.OrdinalIgnoreCase)) targetProcName = "firefox";
                else return null;

                RECT? bounds = null;
                long largestArea = 0;

                EnumWindows((hWnd, lParam) =>
                {
                    if (IsWindowVisible(hWnd) && GetWindowTextLength(hWnd) > 0)
                    {
                        GetWindowThreadProcessId(hWnd, out uint pid);
                        try
                        {
                            var proc = System.Diagnostics.Process.GetProcessById((int)pid);
                            if (proc.ProcessName.Contains(targetProcName, StringComparison.OrdinalIgnoreCase))
                            {
                                if (GetWindowRect(hWnd, out RECT rect))
                                {
                                    // Sometimes there are multiple windows (e.g., extensions, invisible players)
                                    // Choose the largest visible one
                                    long area = (long)rect.Width * rect.Height;
                                    if (area > largestArea && rect.Width > 200 && rect.Height > 200)
                                    {
                                        largestArea = area;
                                        bounds = rect;
                                    }
                                }
                            }
                        }
                        catch { }
                    }
                    return true;
                }, IntPtr.Zero);

                return bounds;
            }
            catch { return null; }
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
