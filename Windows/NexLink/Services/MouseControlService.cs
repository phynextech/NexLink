using System;
using System.Runtime.InteropServices;

namespace NexLink.Services
{
    /// <summary>
    /// MouseControlService — translates NexLink gesture events into real OS mouse actions.
    ///
    /// Called by MainViewModel when it receives mobile touchpad events:
    ///   mouse_move       → MoveRelative(dx, dy)
    ///   mouse_tap        → LeftClick()
    ///   mouse_right_tap  → RightClick()
    ///   mouse_scroll     → Scroll(dy)
    ///
    /// Uses Win32 SendInput via P/Invoke — no extra driver required.
    /// </summary>
    public static class MouseControlService
    {
        // ─── Win32 types ──────────────────────────────────────────────────

        private const uint INPUT_MOUSE = 0;

        private const uint MOUSEEVENTF_MOVE        = 0x0001;
        private const uint MOUSEEVENTF_LEFTDOWN    = 0x0002;
        private const uint MOUSEEVENTF_LEFTUP      = 0x0004;
        private const uint MOUSEEVENTF_RIGHTDOWN   = 0x0008;
        private const uint MOUSEEVENTF_RIGHTUP     = 0x0010;
        private const uint MOUSEEVENTF_MIDDLEDOWN  = 0x0020;
        private const uint MOUSEEVENTF_MIDDLEUP    = 0x0040;
        private const uint MOUSEEVENTF_WHEEL       = 0x0800;
        private const uint MOUSEEVENTF_VIRTUALDESK = 0x4000;

        private const int WHEEL_DELTA = 120;

        [StructLayout(LayoutKind.Sequential)]
        private struct MOUSEINPUT
        {
            public int  dx;
            public int  dy;
            public uint mouseData;
            public uint dwFlags;
            public uint time;
            public IntPtr dwExtraInfo;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct INPUT
        {
            public uint type;
            public MOUSEINPUT mi;
        }

        [DllImport("user32.dll", SetLastError = true)]
        private static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);

        [DllImport("user32.dll")]
        private static extern bool GetCursorPos(out System.Drawing.Point lpPoint);

        // ─── Sensitivity ──────────────────────────────────────────────────
        /// <summary>
        /// Multiplier applied to raw dx/dy values received from the phone.
        /// Increase for faster cursor; decrease for precision mode.
        /// </summary>
        public static float Sensitivity { get; set; } = 1.5f;

        // ─── Public API ───────────────────────────────────────────────────

        /// <summary>Move cursor relative to current position.</summary>
        public static void MoveRelative(float dx, float dy)
        {
            var input = new INPUT
            {
                type = INPUT_MOUSE,
                mi   = new MOUSEINPUT
                {
                    dx       = (int)(dx * Sensitivity),
                    dy       = (int)(dy * Sensitivity),
                    dwFlags  = MOUSEEVENTF_MOVE,
                    mouseData = 0,
                }
            };
            SendInput(1, new[] { input }, Marshal.SizeOf<INPUT>());
        }

        /// <summary>Simulate a left mouse button click (down + up).</summary>
        public static void LeftClick()
        {
            var inputs = new[]
            {
                new INPUT { type = INPUT_MOUSE, mi = new MOUSEINPUT { dwFlags = MOUSEEVENTF_LEFTDOWN } },
                new INPUT { type = INPUT_MOUSE, mi = new MOUSEINPUT { dwFlags = MOUSEEVENTF_LEFTUP   } },
            };
            SendInput((uint)inputs.Length, inputs, Marshal.SizeOf<INPUT>());
        }

        /// <summary>Simulate a right mouse button click (down + up).</summary>
        public static void RightClick()
        {
            var inputs = new[]
            {
                new INPUT { type = INPUT_MOUSE, mi = new MOUSEINPUT { dwFlags = MOUSEEVENTF_RIGHTDOWN } },
                new INPUT { type = INPUT_MOUSE, mi = new MOUSEINPUT { dwFlags = MOUSEEVENTF_RIGHTUP   } },
            };
            SendInput((uint)inputs.Length, inputs, Marshal.SizeOf<INPUT>());
        }

        /// <summary>Simulate a middle mouse button click (down + up).</summary>
        public static void MiddleClick()
        {
            var inputs = new[]
            {
                new INPUT { type = INPUT_MOUSE, mi = new MOUSEINPUT { dwFlags = MOUSEEVENTF_MIDDLEDOWN } },
                new INPUT { type = INPUT_MOUSE, mi = new MOUSEINPUT { dwFlags = MOUSEEVENTF_MIDDLEUP   } },
            };
            SendInput((uint)inputs.Length, inputs, Marshal.SizeOf<INPUT>());
        }

        /// <summary>
        /// Scroll the mouse wheel.
        /// Positive dy = scroll up; negative = scroll down.
        /// </summary>
        public static void Scroll(float dy)
        {
            // Normalize to WHEEL_DELTA units; clamp to reasonable range
            int wheelDelta = (int)(dy * Sensitivity * WHEEL_DELTA / 10f);
            if (wheelDelta == 0) return;

            var input = new INPUT
            {
                type = INPUT_MOUSE,
                mi   = new MOUSEINPUT
                {
                    dwFlags   = MOUSEEVENTF_WHEEL,
                    mouseData = unchecked((uint)wheelDelta),
                }
            };
            SendInput(1, new[] { input }, Marshal.SizeOf<INPUT>());
        }

        /// <summary>Get current cursor screen coordinates (for debug/logging).</summary>
        public static System.Drawing.Point GetCursorPosition()
        {
            GetCursorPos(out var pt);
            return pt;
        }
    }
}
