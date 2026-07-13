using Avalonia.Controls;
using Avalonia.Interactivity;
using XDM.App.ViewModels;

namespace XDM.App;

public partial class MiniWindow : Window
{
    private MainWindow? _mainWindow;

    public MiniWindow()
    {
        InitializeComponent();
    }

    public MiniWindow(MainWindowViewModel viewModel, MainWindow mainWindow)
        : this()
    {
        _mainWindow = mainWindow;
        DataContext = viewModel;
        Closing += (_, eventArgs) =>
        {
            if (App.ExitRequested)
            {
                return;
            }

            eventArgs.Cancel = true;
            Hide();
        };
    }

    public void RestoreAndActivate()
    {
        if (!IsVisible)
        {
            Show();
        }

        if (WindowState == Avalonia.Controls.WindowState.Minimized)
        {
            WindowState = Avalonia.Controls.WindowState.Normal;
        }

        Activate();
    }

    private void OpenFullWindow_Click(object? sender, RoutedEventArgs e)
    {
        Hide();
        _mainWindow?.RestoreAndActivate();
    }
}
