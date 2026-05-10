using System.Windows;
using NexLink.Helpers;

namespace NexLink
{
    public partial class SettingsWindow : Window
    {
        public SettingsWindow()
        {
            InitializeComponent();
            AutoStartCheckbox.IsChecked = RegistryHelper.IsAutoRunEnabled();
        }

        private void AutoStart_Click(object sender, RoutedEventArgs e)
        {
            if (AutoStartCheckbox.IsChecked.HasValue)
            {
                RegistryHelper.SetAutoRun(AutoStartCheckbox.IsChecked.Value);
            }
        }

        private void Close_Click(object sender, RoutedEventArgs e)
        {
            this.Close();
        }
    }
}
