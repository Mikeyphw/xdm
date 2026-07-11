using Avalonia.Controls;
using Avalonia.Input.Platform;
using Avalonia.Interactivity;
using Avalonia.Platform.Storage;
using Avalonia.Threading;
using Microsoft.Extensions.Logging;
using XDM.App.Logging;
using XDM.App.ViewModels;

namespace XDM.App;

public partial class MainWindow : Window
{
    private readonly DispatcherTimer _clipboardTimer = new()
    {
        Interval = TimeSpan.FromSeconds(1.5)
    };
    private string? _lastClipboardText;
    private bool _clipboardReadInProgress;

    public MainWindow()
    {
        InitializeComponent();
        _clipboardTimer.Tick += ClipboardTimer_Tick;
        Opened += (_, _) => _clipboardTimer.Start();
        Closed += (_, _) => _clipboardTimer.Stop();
    }

    public MainWindow(MainWindowViewModel viewModel, ILogger<MainWindow> logger)
        : this()
    {
        DataContext = viewModel;
        AppLog.MainWindowInitialized(logger);
    }

    private async void BrowseDestination_Click(object? sender, RoutedEventArgs e)
    {
        IReadOnlyList<IStorageFolder> folders = await StorageProvider.OpenFolderPickerAsync(
            new FolderPickerOpenOptions
            {
                AllowMultiple = false,
                Title = "Choose download destination"
            });

        string? path = folders.Count > 0 ? folders[0].TryGetLocalPath() : null;
        if (!string.IsNullOrWhiteSpace(path) && DataContext is MainWindowViewModel viewModel)
        {
            viewModel.DestinationFolder = path;
        }
    }

    private async void ClipboardTimer_Tick(object? sender, EventArgs e)
    {
        if (_clipboardReadInProgress
            || DataContext is not MainWindowViewModel { ClipboardMonitoringEnabled: true } viewModel)
        {
            return;
        }

        _clipboardReadInProgress = true;
        try
        {
            IClipboard? clipboard = Clipboard;
            if (clipboard is null)
            {
                return;
            }

            string? text = await clipboard.TryGetTextAsync();
            if (!string.IsNullOrWhiteSpace(text)
                && !string.Equals(text, _lastClipboardText, StringComparison.Ordinal))
            {
                _lastClipboardText = text;
                await viewModel.HandleClipboardTextAsync(text);
            }
        }
        finally
        {
            _clipboardReadInProgress = false;
        }
    }
}
