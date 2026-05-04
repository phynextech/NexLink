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
            private IntPtr _nextViewer;
            public event Action<string>? ClipboardChanged;

            [DllImport("User32.dll")]
            static extern IntPtr SetClipboardViewer(IntPtr hWndNewViewer);
            [DllImport("User32.dll")]
            static extern bool ChangeClipboardChain(IntPtr hWndRemove, IntPtr hWndNewNext);
            [DllImport("user32.dll")]
            static extern IntPtr SendMessage(IntPtr hwnd, int wMsg, IntPtr wParam, IntPtr lParam);

            public ClipboardForm()
            {
                WindowState = FormWindowState.Minimized;
                ShowInTaskbar = false;
                Opacity = 0;
                Load += (s, e) => _nextViewer = SetClipboardViewer(Handle);
            }

            protected override void WndProc(ref Message m)
            {
                const int WM_DRAWCLIPBOARD = 0x0308;
                const int WM_CHANGECBCHAIN = 0x030D;
                switch (m.Msg)
                {
                    case WM_DRAWCLIPBOARD:
                        try
                        {
                            if (System.Windows.Forms.Clipboard.ContainsText())
                                ClipboardChanged?.Invoke(System.Windows.Forms.Clipboard.GetText());
                        }
                        catch { }
                        SendMessage(_nextViewer, m.Msg, m.WParam, m.LParam);
                        break;
                    case WM_CHANGECBCHAIN:
                        if (m.WParam == _nextViewer)
                            _nextViewer = m.LParam;
                        else
                            SendMessage(_nextViewer, m.Msg, m.WParam, m.LParam);
                        break;
                    default:
                        base.WndProc(ref m);
                        break;
                }
            }

            protected override void OnFormClosing(FormClosingEventArgs e)
            {
                ChangeClipboardChain(Handle, _nextViewer);
                base.OnFormClosing(e);
            }
        }
    }
}
