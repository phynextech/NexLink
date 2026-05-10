using System.Windows;

namespace NexLink
{
    public partial class App : Application
    {
        protected override void OnStartup(StartupEventArgs e)
        {
            base.OnStartup(e);
            
            bool startHidden = false;
            foreach (var arg in e.Args)
            {
                if (arg == "--hidden") startHidden = true;
            }

            // Set global exception handler
            DispatcherUnhandledException += (s, ex) =>
            {
                MessageBox.Show($"Unhandled error: {ex.Exception.Message}", "LinkBridge Error",
                    MessageBoxButton.OK, MessageBoxImage.Error);
                ex.Handled = true;
            };

            if (startHidden)
            {
                var mainWindow = new MainWindow();
                mainWindow.WindowState = WindowState.Minimized;
                mainWindow.ShowInTaskbar = false;
                // It will be hidden, but still running
            }
            else
            {
                var mainWindow = new MainWindow();
                mainWindow.Show();
            }
        }
    }
}
