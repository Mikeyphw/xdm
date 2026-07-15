using Avalonia;
using Avalonia.Automation;
using Avalonia.Controls;
using Avalonia.Controls.Primitives;
using Avalonia.Input;
using Avalonia.Media;
using Avalonia.Input.Platform;
using Avalonia.Interactivity;
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
        ("XdmPanelBackground", "#11161D", "#000000"),
        ("XdmPanelAltBackground", "#151B23", "#050505"),
        ("XdmInputBackground", "#191F28", "#000000"),
        ("XdmSecondaryButtonBackground", "#1B222C", "#000000"),
        ("XdmSelectedBackground", "#23344B", "#202020"),
        ("XdmBorderBrush", "#242D39", "#FFFFFF"),
        ("XdmInputBorderBrush", "#334052", "#FFFFFF"),
        ("XdmBrandBrush", "#2F6FEB", "#00A3FF"),
        ("XdmPrimaryForeground", "#F7FAFE", "#FFFFFF"),
        ("XdmPrimarySoftForeground", "#F1F5FA", "#FFFFFF"),
        ("XdmBodyForeground", "#E7EDF6", "#FFFFFF"),
        ("XdmBodySoftForeground", "#E9EEF6", "#FFFFFF"),
        ("XdmSecondaryForeground", "#DDE5F2", "#FFFFFF"),
        ("XdmSecondarySoftForeground", "#C5D0DE", "#F2F2F2"),
        ("XdmSecondaryDimForeground", "#CDD6E2", "#F2F2F2"),
        ("XdmMutedForeground", "#94A1B3", "#E6E6E6"),
        ("XdmSubtleForeground", "#8795A8", "#E6E6E6"),
        ("XdmSubtleDarkForeground", "#8795A8", "#E6E6E6"),
        ("XdmSummaryForeground", "#94A1B3", "#E6E6E6"),
        ("XdmDisabledForeground", "#7B8DA5", "#D0D0D0"),
        ("XdmAccentForeground", "#A9C7FF", "#00FFFF"),
        ("XdmDangerBrush", "#C94F58", "#FF6B6B"),
        ("XdmDangerSoftBrush", "#3A2025", "#000000"),
        ("XdmErrorForeground", "#FF9B9B", "#FF6B6B"),
        ("XdmWarningForeground", "#F5C66A", "#FFFF00"),
        ("XdmSuccessForeground", "#7ED9A6", "#00FF88"),
        ("XdmInfoSoftBrush", "#17243A", "#000000"),
        ("XdmEmptyStateBrush", "#121820", "#000000"),
    ];
    private readonly DispatcherTimer _clipboardTimer = new()
    {
        Interval = TimeSpan.FromSeconds(1.5)
    };
    private string? _lastClipboardText;
    private bool _clipboardReadInProgress;
    private WindowStateStore _windowStateStore;
    private LocalizationService? _localization;
    private int _responsiveShellBand = -1;
    private readonly FlyoutBase _commandPaletteFlyout;
    private readonly FlyoutBase _notificationCenterFlyout;

    public MainWindow()
    {
        _windowStateStore = new WindowStateStore();
        InitializeComponent();
        _commandPaletteFlyout = CommandPaletteButton.Flyout
            ?? throw new InvalidOperationException("The command-palette button must define a flyout.");
        _notificationCenterFlyout = NotificationCenterButton.Flyout
            ?? throw new InvalidOperationException("The notification-center button must define a flyout.");
        ApplyHeaderAutomationText("Command palette", "Notification center");
        _clipboardTimer.Tick += ClipboardTimer_Tick;
        Opened += MainWindow_Opened;
        Closing += MainWindow_Closing;
        Closed += (_, _) => _clipboardTimer.Stop();
        SizeChanged += (_, _) => UpdateResponsiveShell();
        DragDrop.AddDropHandler(this, MainWindow_Drop);
        DragDrop.AddDragOverHandler(this, MainWindow_DragOver);
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
        viewModel.UiActionRequested += ViewModel_UiActionRequested;
        localization.Changed += Localization_Changed;
        ApplyLocalizationAndAccessibility();
        Closed += (_, _) =>
        {
            localization.Changed -= Localization_Changed;
            viewModel.UiActionRequested -= ViewModel_UiActionRequested;
        };
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
        UpdateResponsiveShell();
        DownloadsPage.ApplyPersistedDetailsWidth();
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

    private void ToggleNavigation_Click(object? sender, RoutedEventArgs e)
    {
        NavigationSplitView.IsPaneOpen = !NavigationSplitView.IsPaneOpen;
        UpdateNavigationVisualState();
    }

    private void PrimaryNavigation_SelectionChanged(object? sender, SelectionChangedEventArgs e)
    {
        PageContentHost.Opacity = 0;
        Dispatcher.UIThread.Post(() => PageContentHost.Opacity = 1);
        if (NavigationSplitView.DisplayMode == SplitViewDisplayMode.Overlay)
        {
            NavigationSplitView.IsPaneOpen = false;
            UpdateNavigationVisualState();
        }

        Dispatcher.UIThread.Post(FocusCurrentPageHeading);
    }

    private void UpdateResponsiveShell()
    {
        double width = Bounds.Width > 0 ? Bounds.Width : Width;
        ApplyResponsiveShell(width);
    }

    internal void ApplyResponsiveShell(double width)
    {
        int shellBand = width < 900 ? 0 : width < 1180 ? 1 : 2;
        if (shellBand != _responsiveShellBand)
        {
            _responsiveShellBand = shellBand;
            switch (shellBand)
            {
                case 0:
                    NavigationSplitView.DisplayMode = SplitViewDisplayMode.Overlay;
                    NavigationSplitView.IsPaneOpen = false;
                    break;
                case 1:
                    NavigationSplitView.DisplayMode = SplitViewDisplayMode.CompactInline;
                    NavigationSplitView.IsPaneOpen = false;
                    break;
                default:
                    NavigationSplitView.DisplayMode = SplitViewDisplayMode.CompactInline;
                    NavigationSplitView.IsPaneOpen = true;
                    break;
            }
        }

        SetClass("narrow-shell", width < 1040);
        SetClass("compact-content", width < 900);
        SectionIcon.IsVisible = width >= 820;
        OperationStatusBanner.MaxWidth = width < 1040 ? 280 : 390;
        UpdateNavigationVisualState();
    }

    private void UpdateNavigationVisualState()
    {
        bool compact = NavigationSplitView.DisplayMode != SplitViewDisplayMode.Overlay
            && !NavigationSplitView.IsPaneOpen;
        SetClass("compact-nav", compact);
        ContentNavigationToggle.IsVisible = Bounds.Width < 1180 || !NavigationSplitView.IsPaneOpen;
    }

    private void SetClass(string className, bool enabled)
    {
        if (enabled)
        {
            if (!Classes.Contains(className))
            {
                Classes.Add(className);
            }
        }
        else
        {
            Classes.Remove(className);
        }
    }

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
        ApplyHeaderAutomationText(
            _localization.Get("ui_command_palette", "Command palette"),
            _localization.Get("ui_notification_center", "Notification center"));
    }

    private void ApplyHeaderAutomationText(string commandPaletteName, string notificationCenterName)
    {
        AutomationProperties.SetName(CommandPaletteButton, commandPaletteName);
        AutomationProperties.SetName(NotificationCenterButton, notificationCenterName);
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
        if (eventArgs.Key == Key.F6)
        {
            ToggleShellFocus();
            eventArgs.Handled = true;
            return;
        }

        if (eventArgs.Key == Key.Escape && _commandPaletteFlyout.IsOpen)
        {
            _commandPaletteFlyout.Hide();
            eventArgs.Handled = true;
            return;
        }

        if (eventArgs.Key == Key.Escape
            && NavigationSplitView.DisplayMode == SplitViewDisplayMode.Overlay
            && NavigationSplitView.IsPaneOpen)
        {
            NavigationSplitView.IsPaneOpen = false;
            UpdateNavigationVisualState();
            FocusCurrentPageHeading();
            eventArgs.Handled = true;
            return;
        }

        if (DataContext is not MainWindowViewModel viewModel)
        {
            return;
        }

        bool control = (eventArgs.KeyModifiers & KeyModifiers.Control) != 0;
        if (control && eventArgs.Key == Key.K)
        {
            OpenCommandPalette();
            eventArgs.Handled = true;
            return;
        }

        if (control && eventArgs.Key == Key.Z)
        {
            viewModel.UndoHistoryRemovalCommand.Execute(null);
            eventArgs.Handled = true;
            return;
        }

        if (control && eventArgs.Key == Key.N)
        {
            viewModel.SelectSection("downloads");
            DownloadsPage.FocusNewDownload();
            eventArgs.Handled = true;
            return;
        }

        if (control && eventArgs.Key == Key.F)
        {
            viewModel.SelectSection("downloads");
            DownloadsPage.FocusSearch();
            eventArgs.Handled = true;
            return;
        }

        if (control && eventArgs.Key == Key.S && viewModel.IsSettingsVisible)
        {
            if (viewModel.SaveSettingsCommand.CanExecute(null))
            {
                viewModel.SaveSettingsCommand.Execute(null);
            }
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

    private void CommandPaletteButton_Click(object? sender, RoutedEventArgs e)
        => OpenCommandPalette();

    private void OpenCommandPalette()
    {
        if (DataContext is MainWindowViewModel viewModel)
        {
            viewModel.IsCommandPaletteOpen = true;
            viewModel.CommandPaletteQuery = string.Empty;
        }

        _commandPaletteFlyout.ShowAt(CommandPaletteButton);
        Dispatcher.UIThread.Post(() =>
        {
            CommandPaletteInput.Focus();
            CommandPaletteInput.SelectAll();
        });
    }

    private void CommandPaletteFlyout_Opened(object? sender, EventArgs e)
    {
        if (DataContext is MainWindowViewModel viewModel)
        {
            viewModel.IsCommandPaletteOpen = true;
        }

        Dispatcher.UIThread.Post(() => CommandPaletteInput.Focus());
    }

    private void CommandPaletteFlyout_Closed(object? sender, EventArgs e)
    {
        if (DataContext is MainWindowViewModel viewModel)
        {
            viewModel.IsCommandPaletteOpen = false;
            viewModel.CommandPaletteQuery = string.Empty;
        }
    }

    private async void CommandPaletteInput_KeyDown(object? sender, KeyEventArgs e)
    {
        if (e.Key == Key.Down && CommandPaletteList.ItemCount > 0)
        {
            CommandPaletteList.SelectedIndex = Math.Min(
                CommandPaletteList.ItemCount - 1,
                CommandPaletteList.SelectedIndex + 1);
            if (CommandPaletteList.SelectedItem is { } selectedItem)
            {
                CommandPaletteList.ScrollIntoView(selectedItem);
            }
            e.Handled = true;
            return;
        }

        if (e.Key == Key.Up && CommandPaletteList.ItemCount > 0)
        {
            CommandPaletteList.SelectedIndex = Math.Max(0, CommandPaletteList.SelectedIndex - 1);
            if (CommandPaletteList.SelectedItem is { } selectedItem)
            {
                CommandPaletteList.ScrollIntoView(selectedItem);
            }
            e.Handled = true;
            return;
        }

        if (e.Key == Key.Enter)
        {
            await ExecuteSelectedCommandPaletteItemAsync();
            e.Handled = true;
        }
    }

    private async void CommandPaletteList_DoubleTapped(object? sender, TappedEventArgs e)
        => await ExecuteSelectedCommandPaletteItemAsync();

    private async Task ExecuteSelectedCommandPaletteItemAsync()
    {
        if (DataContext is not MainWindowViewModel viewModel)
        {
            return;
        }

        CommandPaletteItemViewModel? item = CommandPaletteList.SelectedItem as CommandPaletteItemViewModel
            ?? viewModel.FilteredCommandPaletteItems.FirstOrDefault();
        if (item is not null)
        {
            await viewModel.ExecuteCommandPaletteItemAsync(item);
            _commandPaletteFlyout.Hide();
        }
    }

    private void DismissNotification_Click(object? sender, RoutedEventArgs e)
    {
        if (sender is Button { DataContext: NotificationCenterEntryViewModel entry }
            && DataContext is MainWindowViewModel viewModel)
        {
            viewModel.DismissNotificationCommand.Execute(entry);
        }
    }

    private void ViewModel_UiActionRequested(object? sender, string action)
    {
        Dispatcher.UIThread.Post(() =>
        {
            switch (action)
            {
                case "focus-new-download":
                    DownloadsPage.FocusNewDownload();
                    break;
                case "focus-download-search":
                    DownloadsPage.FocusSearch();
                    break;
                case "open-notifications":
                    _notificationCenterFlyout.ShowAt(NotificationCenterButton);
                    break;
                case "open-mini-window":
                    (Application.Current as App)?.ShowMiniWindow();
                    break;
            }
        });
    }

    private void MainWindow_DragOver(object? sender, DragEventArgs e)
    {
        bool acceptsFiles = e.DataTransfer.Formats.Contains(DataFormat.File);
        bool acceptsText = e.DataTransfer.Formats.Contains(DataFormat.Text);
        e.DragEffects = acceptsFiles || acceptsText
            ? DragDropEffects.Copy
            : DragDropEffects.None;
    }

    private async void MainWindow_Drop(object? sender, DragEventArgs e)
    {
        if (DataContext is not MainWindowViewModel viewModel)
        {
            return;
        }

        string[] paths = e.DataTransfer.TryGetFiles()?
            .Select(static file => file.Path.LocalPath)
            .Where(static path => !string.IsNullOrWhiteSpace(path))
            .ToArray() ?? [];
        if (paths.Length > 0)
        {
            await viewModel.HandleDroppedFilesAsync(paths);
            e.DragEffects = DragDropEffects.Copy;
            return;
        }

        string? text = e.DataTransfer.TryGetText();
        if (!string.IsNullOrWhiteSpace(text))
        {
            await viewModel.HandleDroppedTextAsync(text);
            e.DragEffects = DragDropEffects.Copy;
        }
    }

    private void ToggleShellFocus()
    {
        if (PrimaryNavigation.IsKeyboardFocusWithin)
        {
            FocusCurrentPageHeading();
            return;
        }

        if (NavigationSplitView.DisplayMode == SplitViewDisplayMode.Overlay)
        {
            NavigationSplitView.IsPaneOpen = true;
            UpdateNavigationVisualState();
        }

        PrimaryNavigation.Focus();
    }

    private void FocusCurrentPageHeading()
    {
        PageTitleText.Focus();
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
