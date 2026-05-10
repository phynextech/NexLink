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
        private const uint MOUSEEVENTF_HWHEEL      = 0x01000;
        private const uint MOUSEEVENTF_VIRTUALDESK = 0x4000;

        private const int WHEEL_DELTA = 120;

        // ─── Keyboard INPUT types ─────────────────────────────────────────
        private const uint INPUT_KEYBOARD       = 1;
        private const uint KEYEVENTF_KEYUP      = 0x0002;
        private const uint KEYEVENTF_EXTENDEDKEY = 0x0001;

        [StructLayout(LayoutKind.Sequential)]
        private struct KEYBDINPUT
        {
            public ushort wVk;
            public ushort wScan;
            public uint   dwFlags;
            public uint   time;
            public IntPtr dwExtraInfo;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct INPUT_FULL
        {
            public uint type;
            [MarshalAs(UnmanagedType.ByValArray, SizeConst = 40)]
            public byte[] data;
        }

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

        [DllImport("user32.dll", SetLastError = true)]
        private static extern uint SendInput(uint nInputs, [In] IntPtr pInputs, int cbSize);

        [DllImport("user32.dll")]
        private static extern short VkKeyScan(char ch);

        [DllImport("user32.dll", CharSet = CharSet.Auto)]
        private static extern ushort MapVirtualKey(ushort uCode, uint uMapType);

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

        /// <summary>Scroll the mouse wheel horizontally. Positive = right.</summary>
        public static void HScroll(float dx)
        {
            int wheelDelta = (int)(dx * Sensitivity * WHEEL_DELTA / 10f);
            if (wheelDelta == 0) return;
            var input = new INPUT
            {
                type = INPUT_MOUSE,
                mi   = new MOUSEINPUT { dwFlags = MOUSEEVENTF_HWHEEL, mouseData = unchecked((uint)wheelDelta) }
            };
            SendInput(1, new[] { input }, Marshal.SizeOf<INPUT>());
        }

        /// <summary>Press and release a named key (matching the codes sent from the Android keyboard).</summary>
        public static void SendKeyPress(string keyName)
        {
            ushort vk = GetVk(keyName);
            if (vk == 0) return;

            bool extended = IsExtended(vk);
            uint extFlag = extended ? KEYEVENTF_EXTENDEDKEY : 0;

            // Key down
            var down = new KEYBDINPUT_INPUT { type = INPUT_KEYBOARD };
            down.ki.wVk    = vk;
            down.ki.dwFlags = extFlag;
            SendKbInput(down);

            // Key up
            var up = new KEYBDINPUT_INPUT { type = INPUT_KEYBOARD };
            up.ki.wVk     = vk;
            up.ki.dwFlags = KEYEVENTF_KEYUP | extFlag;
            SendKbInput(up);
        }

        private static unsafe void SendKbInput(KEYBDINPUT_INPUT inp)
        {
            int size = Marshal.SizeOf<KEYBDINPUT_INPUT>();
            IntPtr ptr = Marshal.AllocHGlobal(size);
            try
            {
                Marshal.StructureToPtr(inp, ptr, false);
                SendInput(1, ptr, size);
            }
            finally { Marshal.FreeHGlobal(ptr); }
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct KEYBDINPUT_INPUT
        {
            public uint type;
            public KEYBDINPUT ki;
        }

        private static bool IsExtended(ushort vk) => vk switch {
            0x21 or 0x22 or 0x23 or 0x24 or 0x25 or 0x26 or 0x27 or 0x28 => true, // nav keys
            0x2D or 0x2E => true, // Insert, Delete
            _ => false
        };

        private static ushort GetVk(string key) => key.ToLower() switch
        {
            "escape" or "esc"  => 0x1B,
            "tab"             => 0x09,
            "capslock"        => 0x14,
            "shift"           => 0x10,
            "control" or "ctrl" => 0x11,
            "alt"             => 0x12,
            "win"             => 0x5B,
            "fn"              => 0,     // no standard VK
            "space"           => 0x20,
            "enter"           => 0x0D,
            "back" or "backspace" or "bksp" => 0x08,
            "delete" or "del" => 0x2E,
            "home"            => 0x24,
            "end"             => 0x23,
            "pageup"          => 0x21,
            "pagedown"        => 0x22,
            "insert"          => 0x2D,
            "arrowup"         => 0x26,
            "arrowdown"       => 0x28,
            "arrowleft"       => 0x25,
            "arrowright"      => 0x27,
            "f1"  => 0x70, "f2"  => 0x71, "f3"  => 0x72, "f4"  => 0x73,
            "f5"  => 0x74, "f6"  => 0x75, "f7"  => 0x76, "f8"  => 0x77,
            "f9"  => 0x78, "f10" => 0x79, "f11" => 0x7A, "f12" => 0x7B,
            _ => key.Length == 1 ? (ushort)(VkKeyScan(key[0]) & 0xFF) : (ushort)0
        };

        /// <summary>Get current cursor screen coordinates (for debug/logging).</summary>
        public static System.Drawing.Point GetCursorPosition()
        {
            GetCursorPos(out var pt);
            return pt;
        }
    }
}
