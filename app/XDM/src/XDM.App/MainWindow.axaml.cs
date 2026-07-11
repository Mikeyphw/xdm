using Avalonia.Controls;
using Microsoft.Extensions.Logging;
using XDM.App.Logging;
using XDM.App.ViewModels;

namespace XDM.App;

public partial class MainWindow : Window
{
    public MainWindow()
    {
        InitializeComponent();
    }

    public MainWindow(MainWindowViewModel viewModel, ILogger<MainWindow> logger)
        : this()
    {
        DataContext = viewModel;
        AppLog.MainWindowInitialized(logger);
    }
}
