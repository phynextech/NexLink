using System.Windows;

namespace NexLink
{
    public partial class App : Application
    {
        protected override void OnStartup(StartupEventArgs e)
        {
            base.OnStartup(e);
            // Set global exception handler
            DispatcherUnhandledException += (s, ex) =>
            {
                MessageBox.Show($"Unhandled error: {ex.Exception.Message}", "LinkBridge Error",
                    MessageBoxButton.OK, MessageBoxImage.Error);
                ex.Handled = true;
            };
        }
    }
}
