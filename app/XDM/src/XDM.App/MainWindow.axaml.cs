using Avalonia.Automation;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Media;
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
    private static readonly (string Key, string Normal, string HighContrast)[] Palette =
    [
        ("XdmWindowBackground", "#0E1116", "#000000"),
        ("XdmPanelBackground", "#12171E", "#000000"),
        ("XdmPanelAltBackground", "#151B23", "#050505"),
        ("XdmInputBackground", "#171D25", "#000000"),
        ("XdmSecondaryButtonBackground", "#1B212A", "#000000"),
        ("XdmSelectedBackground", "#26354A", "#202020"),
        ("XdmBorderBrush", "#202833", "#FFFFFF"),
        ("XdmInputBorderBrush", "#273240", "#FFFFFF"),
        ("XdmBrandBrush", "#4F8CFF", "#00A3FF"),
        ("XdmPrimaryForeground", "#F3F7FC", "#FFFFFF"),
        ("XdmPrimarySoftForeground", "#F1F5FA", "#FFFFFF"),
        ("XdmBodyForeground", "#E8EEF7", "#FFFFFF"),
        ("XdmBodySoftForeground", "#E9EEF6", "#FFFFFF"),
        ("XdmSecondaryForeground", "#DDE5F2", "#FFFFFF"),
        ("XdmSecondarySoftForeground", "#BAC7D8", "#F2F2F2"),
        ("XdmSecondaryDimForeground", "#C8D1DE", "#F2F2F2"),
        ("XdmMutedForeground", "#7F8B9B", "#E6E6E6"),
        ("XdmSubtleForeground", "#718095", "#E6E6E6"),
        ("XdmSubtleDarkForeground", "#687586", "#E6E6E6"),
        ("XdmSummaryForeground", "#8794A5", "#E6E6E6"),
        ("XdmDisabledForeground", "#58739A", "#D0D0D0"),
        ("XdmAccentForeground", "#8FB5FF", "#00FFFF"),
        ("XdmErrorForeground", "#FF8F8F", "#FF6B6B"),
        ("XdmWarningForeground", "#F5C66A", "#FFFF00"),
    ];
    private readonly DispatcherTimer _clipboardTimer = new()
    {
        Interval = TimeSpan.FromSeconds(1.5)
    };
    private string? _lastClipboardText;
    private bool _clipboardReadInProgress;
    private WindowStateStore _windowStateStore;
    private LocalizationService? _localization;

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
        WindowStateStore windowStateStore,
        LocalizationService localization)
        : this()
    {
        _windowStateStore = windowStateStore;
        _localization = localization;
        DataContext = viewModel;
        localization.Changed += Localization_Changed;
        ApplyLocalizationAndAccessibility();
        Closed += (_, _) => localization.Changed -= Localization_Changed;
        AppLog.MainWindowInitialized(logger);
    }

    public void RestoreAndActivate()
    {
        if (!IsVisible)
        {
            ShowInTaskbar = true;
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
        ApplyLocalizationAndAccessibility();
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
        bool hideToTray = !App.ExitRequested;
        if (hideToTray)
        {
            eventArgs.Cancel = true;
        }

        WindowPlacementState state = new(
            Position.X,
            Position.Y,
            Math.Max(MinWidth, Bounds.Width),
            Math.Max(MinHeight, Bounds.Height),
            WindowState == Avalonia.Controls.WindowState.Maximized);
        await _windowStateStore.SaveAsync(state);
        if (hideToTray)
        {
            Hide();
            ShowInTaskbar = false;
            if (DataContext is MainWindowViewModel viewModel)
            {
                viewModel.OperationMessage = "XDM is still running in the system tray.";
            }
        }
    }

    private async void BrowseDestination_Click(object? sender, RoutedEventArgs e)
    {
        IReadOnlyList<IStorageFolder> folders = await StorageProvider.OpenFolderPickerAsync(
            new FolderPickerOpenOptions
            {
                AllowMultiple = false,
                Title = Localize("picker_download_destination", "Choose download destination")
            });

        string? path = folders.Count > 0 ? folders[0].TryGetLocalPath() : null;
        if (!string.IsNullOrWhiteSpace(path) && DataContext is MainWindowViewModel viewModel)
        {
            viewModel.DestinationFolder = path;
        }
    }

    private async void BrowseConversionSource_Click(object? sender, RoutedEventArgs e)
    {
        IReadOnlyList<IStorageFile> files = await StorageProvider.OpenFilePickerAsync(
            new FilePickerOpenOptions
            {
                AllowMultiple = false,
                Title = Localize("picker_conversion_source", "Choose media to convert")
            });

        string? path = files.Count > 0 ? files[0].TryGetLocalPath() : null;
        if (!string.IsNullOrWhiteSpace(path) && DataContext is MainWindowViewModel viewModel)
        {
            viewModel.SetConversionSourcePath(path);
        }
    }


    private async void BrowseSettingsImport_Click(object? sender, RoutedEventArgs e)
    {
        IReadOnlyList<IStorageFile> files = await StorageProvider.OpenFilePickerAsync(
            new FilePickerOpenOptions
            {
                AllowMultiple = false,
                Title = Localize("picker_settings_import", "Choose modern or legacy XDM settings")
            });
        string? path = files.Count > 0 ? files[0].TryGetLocalPath() : null;
        if (!string.IsNullOrWhiteSpace(path) && DataContext is MainWindowViewModel viewModel)
        {
            viewModel.SettingsTransferPath = path;
        }
    }

    private async void BrowseSettingsDirectory_Click(object? sender, RoutedEventArgs e)
    {
        IReadOnlyList<IStorageFolder> folders = await StorageProvider.OpenFolderPickerAsync(
            new FolderPickerOpenOptions
            {
                AllowMultiple = false,
                Title = Localize("picker_settings_directory", "Choose legacy XDM settings directory")
            });
        string? path = folders.Count > 0 ? folders[0].TryGetLocalPath() : null;
        if (!string.IsNullOrWhiteSpace(path) && DataContext is MainWindowViewModel viewModel)
        {
            viewModel.SettingsTransferPath = path;
        }
    }

    private async void BrowseSettingsExport_Click(object? sender, RoutedEventArgs e)
    {
        IStorageFile? file = await StorageProvider.SaveFilePickerAsync(
            new FilePickerSaveOptions
            {
                Title = Localize("picker_settings_export", "Export XDM settings"),
                SuggestedFileName = "xdm-settings.json",
                DefaultExtension = "json"
            });
        string? path = file?.TryGetLocalPath();
        if (!string.IsNullOrWhiteSpace(path) && DataContext is MainWindowViewModel viewModel)
        {
            viewModel.SettingsTransferPath = path;
        }
    }


    private async void BrowseRelocationDestination_Click(object? sender, RoutedEventArgs e)
    {
        IStorageFile? file = await StorageProvider.SaveFilePickerAsync(
            new FilePickerSaveOptions
            {
                Title = Localize("picker_relocation_destination", "Choose new download path"),
                SuggestedFileName = DataContext is MainWindowViewModel { SelectedDownload: { } selected }
                    ? selected.FileName
                    : "download.bin"
            });
        string? path = file?.TryGetLocalPath();
        if (!string.IsNullOrWhiteSpace(path) && DataContext is MainWindowViewModel viewModel)
        {
            viewModel.RelocationDestinationPath = path;
        }
    }

    private async void BrowseHistoryImport_Click(object? sender, RoutedEventArgs e)
    {
        IReadOnlyList<IStorageFile> files = await StorageProvider.OpenFilePickerAsync(
            new FilePickerOpenOptions
            {
                AllowMultiple = false,
                Title = Localize("picker_history_import", "Choose XDM download list or plain URL list")
            });
        string? path = files.Count > 0 ? files[0].TryGetLocalPath() : null;
        if (!string.IsNullOrWhiteSpace(path) && DataContext is MainWindowViewModel viewModel)
        {
            viewModel.HistoryTransferPath = path;
        }
    }

    private async void BrowseHistoryExport_Click(object? sender, RoutedEventArgs e)
    {
        IStorageFile? file = await StorageProvider.SaveFilePickerAsync(
            new FilePickerSaveOptions
            {
                Title = Localize("picker_history_export", "Export XDM download list"),
                SuggestedFileName = "xdm-downloads.json",
                DefaultExtension = "json"
            });
        string? path = file?.TryGetLocalPath();
        if (!string.IsNullOrWhiteSpace(path) && DataContext is MainWindowViewModel viewModel)
        {
            viewModel.HistoryTransferPath = path;
        }
    }

    private string Localize(string key, string fallback)
        => _localization?.Get(key, fallback) ?? fallback;

    private void Localization_Changed(object? sender, EventArgs eventArgs)
    {
        if (Dispatcher.UIThread.CheckAccess())
        {
            ApplyLocalizationAndAccessibility();
        }
        else
        {
            Dispatcher.UIThread.Post(ApplyLocalizationAndAccessibility);
        }
    }

    private void ApplyLocalizationAndAccessibility()
    {
        if (_localization is null)
        {
            return;
        }

        FlowDirection = _localization.IsRightToLeft
            ? Avalonia.Media.FlowDirection.RightToLeft
            : Avalonia.Media.FlowDirection.LeftToRight;
        ApplyPalette(_localization.HighContrastEnabled);
        if (_localization.HighContrastEnabled)
        {
            if (!Classes.Contains("high-contrast"))
            {
                Classes.Add("high-contrast");
            }
        }
        else
        {
            Classes.Remove("high-contrast");
        }

        double scale = _localization.UiScaleFactor;
        UiScaleRoot.LayoutTransform = Math.Abs(scale - 1d) < 0.001d
            ? null
            : new ScaleTransform(scale, scale);
        AutomationProperties.SetLiveSetting(
            OperationStatusText,
            _localization.AnnounceStatusChanges ? AutomationLiveSetting.Polite : AutomationLiveSetting.Off);
    }

    private void ApplyPalette(bool highContrast)
    {
        foreach ((string key, string normal, string contrast) in Palette)
        {
            Resources[key] = new SolidColorBrush(Color.Parse(highContrast ? contrast : normal));
        }
    }

    private void MainWindow_KeyDown(object? sender, KeyEventArgs eventArgs)
    {
        if (DataContext is not MainWindowViewModel viewModel)
        {
            return;
        }

        bool control = (eventArgs.KeyModifiers & KeyModifiers.Control) != 0;
        if (control && eventArgs.Key == Key.N)
        {
            viewModel.SelectSection("downloads");
            NewDownloadUrlsInput.Focus();
            eventArgs.Handled = true;
            return;
        }

        if (control && eventArgs.Key == Key.F)
        {
            viewModel.SelectSection("downloads");
            DownloadSearchInput.Focus();
            eventArgs.Handled = true;
            return;
        }

        if (control && TryGetSectionIndex(eventArgs.Key, out int sectionIndex))
        {
            if (sectionIndex < viewModel.Sections.Count)
            {
                viewModel.SelectedSection = viewModel.Sections[sectionIndex];
                eventArgs.Handled = true;
            }
            return;
        }

        if (control && eventArgs.Key == Key.P)
        {
            viewModel.PauseBulkCommand.Execute(null);
            eventArgs.Handled = true;
            return;
        }

        if (control && eventArgs.Key == Key.R)
        {
            viewModel.ResumeBulkCommand.Execute(null);
            eventArgs.Handled = true;
            return;
        }

        if (eventArgs.Key == Key.Escape)
        {
            viewModel.CancelMediaDownloadCommand.Execute(null);
            viewModel.CancelSelectedConversionCommand.Execute(null);
            viewModel.CancelPendingCompletionActionCommand.Execute(null);
            eventArgs.Handled = true;
        }
    }

    private static bool TryGetSectionIndex(Key key, out int index)
    {
        index = key switch
        {
            Key.D1 => 0,
            Key.D2 => 1,
            Key.D3 => 2,
            Key.D4 => 3,
            Key.D5 => 4,
            Key.D6 => 5,
            Key.D7 => 6,
            Key.D8 => 7,
            _ => -1,
        };
        return index >= 0;
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
