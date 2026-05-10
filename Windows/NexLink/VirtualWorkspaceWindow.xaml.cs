using System.Windows;
using System.Windows.Input;

namespace NexLink
{
    public partial class VirtualWorkspaceWindow : Window
    {
        public VirtualWorkspaceWindow()
        {
            InitializeComponent();
        }

        private void Header_MouseDown(object sender, MouseButtonEventArgs e)
        {
            if (e.ChangedButton == MouseButton.Left)
                this.DragMove();
        }
    }
}
