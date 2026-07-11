using Avalonia.Controls;
using Microsoft.Extensions.Logging;
using XDM.App.ViewModels;

namespace XDM.App;

public partial class MainWindow : Window
{
    public MainWindow(MainWindowViewModel viewModel, ILogger<MainWindow> logger)
    {
        InitializeComponent();
        DataContext = viewModel;
        logger.LogInformation("XDM Avalonia bootstrap window initialized.");
    }
}
