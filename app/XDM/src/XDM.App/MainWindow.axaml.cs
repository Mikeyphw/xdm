using Avalonia.Controls;
using Avalonia.Input.Platform;
using Avalonia.Interactivity;
using Avalonia.Platform.Storage;
using Avalonia.Threading;
using Microsoft.Extensions.Logging;
using XDM.App.Logging;
using XDM.App.Services;
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
    private WindowStateStore _windowStateStore;

    public MainWindow()
    {
        _windowStateStore = new WindowStateStore();
        InitializeComponent();
        _clipboardTimer.Tick += ClipboardTimer_Tick;
        Opened += MainWindow_Opened;
        Closing += MainWindow_Closing;
        Closed += (_, _) => _clipboardTimer.Stop();
    }

    public MainWindow(
        MainWindowViewModel viewModel,
        ILogger<MainWindow> logger,
        WindowStateStore windowStateStore)
        : this()
    {
        _windowStateStore = windowStateStore;
        DataContext = viewModel;
        AppLog.MainWindowInitialized(logger);
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


    private async void MainWindow_Opened(object? sender, EventArgs eventArgs)
    {
        _clipboardTimer.Start();
        if (App.LaunchOptions.ResetWindowState)
        {
            await _windowStateStore.ResetAsync();
            return;
        }

        WindowPlacementState? state = await _windowStateStore.LoadAsync();
        if (state is null)
        {
            return;
        }

        Width = Math.Max(MinWidth, state.Width);
        Height = Math.Max(MinHeight, state.Height);
        Position = new Avalonia.PixelPoint(state.X, state.Y);
        if (state.IsMaximized)
        {
            WindowState = Avalonia.Controls.WindowState.Maximized;
        }
    }

    private async void MainWindow_Closing(object? sender, WindowClosingEventArgs eventArgs)
    {
        WindowPlacementState state = new(
            Position.X,
            Position.Y,
            Math.Max(MinWidth, Bounds.Width),
            Math.Max(MinHeight, Bounds.Height),
            WindowState == Avalonia.Controls.WindowState.Maximized);
        await _windowStateStore.SaveAsync(state);
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
