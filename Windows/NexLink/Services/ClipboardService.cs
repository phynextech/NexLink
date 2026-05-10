using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;
using System.Windows.Forms;

namespace NexLink.Services
{
    public class ClipboardService : IDisposable
    {
        public event Action<string>? ClipboardChanged;

        // Use a hidden form window for clipboard notification
        private ClipboardForm? _form;
        private Thread? _thread;

        public void Start()
        {
            _thread = new Thread(() =>
            {
                _form = new ClipboardForm();
                _form.ClipboardChanged += content => ClipboardChanged?.Invoke(content);
                System.Windows.Forms.Application.Run(_form);
            });
            _thread.SetApartmentState(ApartmentState.STA);
            _thread.IsBackground = true;
            _thread.Start();
        }

        public static string GetClipboardText()
        {
            try
            {
                if (System.Windows.Clipboard.ContainsText())
                    return System.Windows.Clipboard.GetText();
            }
            catch { }
            return "";
        }

        public static void SetClipboardText(string text)
        {
            try
            {
                System.Windows.Application.Current.Dispatcher.Invoke(() =>
                    System.Windows.Clipboard.SetText(text));
            }
            catch { }
        }

        public void Dispose()
        {
            _form?.Invoke((Action)(() => System.Windows.Forms.Application.ExitThread()));
        }

        // Hidden WinForms window that monitors clipboard changes
        private class ClipboardForm : Form
        {
            public event Action<string>? ClipboardChanged;

            [DllImport("user32.dll", SetLastError = true)]
            [return: MarshalAs(UnmanagedType.Bool)]
            static extern bool AddClipboardFormatListener(IntPtr hwnd);

            [DllImport("user32.dll", SetLastError = true)]
            [return: MarshalAs(UnmanagedType.Bool)]
            static extern bool RemoveClipboardFormatListener(IntPtr hwnd);

            public ClipboardForm()
            {
                WindowState = FormWindowState.Minimized;
                ShowInTaskbar = false;
                Opacity = 0;
                Load += (s, e) => AddClipboardFormatListener(Handle);
            }

            protected override void WndProc(ref Message m)
            {
                const int WM_CLIPBOARDUPDATE = 0x031D;
                switch (m.Msg)
                {
                    case WM_CLIPBOARDUPDATE:
                        try
                        {
                            if (System.Windows.Forms.Clipboard.ContainsText())
                                ClipboardChanged?.Invoke(System.Windows.Forms.Clipboard.GetText());
                        }
                        catch { }
                        break;
                    default:
                        base.WndProc(ref m);
                        break;
                }
            }

            protected override void OnFormClosing(FormClosingEventArgs e)
            {
                RemoveClipboardFormatListener(Handle);
                base.OnFormClosing(e);
            }
        }
    }
}
