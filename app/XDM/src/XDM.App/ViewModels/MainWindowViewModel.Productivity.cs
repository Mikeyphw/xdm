using System.Collections.ObjectModel;
using Avalonia;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using XDM.App.Services;
using XDM.Core.Downloads;

namespace XDM.App.ViewModels;

public partial class MainWindowViewModel
{
    private readonly INotificationCenterService _notificationCenter;
    private readonly DesktopProductivityStateStore _productivityStateStore;
    private readonly HashSet<string> _mutedDownloadNotifications = new(StringComparer.Ordinal);
    private bool _applyingProductivityState;

    public ObservableCollection<NotificationCenterEntryViewModel> Notifications { get; } = [];

    public ObservableCollection<CommandPaletteItemViewModel> CommandPaletteItems { get; } = [];

    public IReadOnlyList<DownloadListDensity> DownloadDensityOptions { get; } =
        Enum.GetValues<DownloadListDensity>();

    public event EventHandler<string>? UiActionRequested;

    [ObservableProperty]
    private bool isNotificationCenterOpen;

    [ObservableProperty]
    private bool isCommandPaletteOpen;

    [ObservableProperty]
    private string commandPaletteQuery = string.Empty;

    [ObservableProperty]
    private bool isClipboardReviewVisible;

    [ObservableProperty]
    private string clipboardReviewSummary = string.Empty;

    [ObservableProperty]
    private string clipboardReviewText = string.Empty;

    [ObservableProperty]
    private bool canUndoHistoryRemoval;

    [ObservableProperty]
    private bool selectedDownloadNotificationsEnabled = true;

    [ObservableProperty]
    private DownloadListDensity downloadDensity = DownloadListDensity.Comfortable;

    [ObservableProperty]
    private double downloadDetailsPaneWidth = 360;

    [ObservableProperty]
    private bool showDownloadSource = true;

    [ObservableProperty]
    private bool showDownloadQueue = true;

    [ObservableProperty]
    private bool showDownloadPriority = true;

    [ObservableProperty]
    private bool showDownloadTags = true;

    [ObservableProperty]
    private bool showDownloadSpeed = true;

    [ObservableProperty]
    private bool showDownloadRemaining = true;

    public bool HasNotifications => Notifications.Count > 0;

    public int NotificationCount => Notifications.Count;

    public bool IsCompactDownloadDensity => DownloadDensity == DownloadListDensity.Compact;

    public string AggregateProgressText
    {
        get
        {
            DownloadItemViewModel[] active = Downloads
                .Where(static item => item.State is DownloadState.Connecting or DownloadState.Downloading or DownloadState.Finalizing)
                .ToArray();
            long knownTotal = active.Sum(static item => item.TotalBytes ?? 0);
            if (active.Length == 0 || knownTotal <= 0)
            {
                return active.Length == 0 ? "Idle" : "Active";
            }

            double weightedProgress = active.Sum(static item =>
                item.TotalBytes is > 0
                    ? item.ProgressPercent * item.TotalBytes.Value
                    : 0d) / knownTotal;
            return $"{Math.Clamp(weightedProgress, 0d, 100d):0}%";
        }
    }

    public Thickness DownloadItemPadding => IsCompactDownloadDensity
        ? new Thickness(10, 6)
        : new Thickness(12);


    public IEnumerable<DownloadItemViewModel> MiniDownloads
        => Downloads
            .Where(static item => item.State is DownloadState.Queued
                or DownloadState.Connecting
                or DownloadState.Downloading
                or DownloadState.Paused
                or DownloadState.Finalizing)
            .OrderByDescending(static item => item.State is DownloadState.Connecting
                or DownloadState.Downloading
                or DownloadState.Finalizing)
            .ThenByDescending(static item => item.ProgressPercent)
            .Take(8);

    public IEnumerable<CommandPaletteItemViewModel> FilteredCommandPaletteItems
    {
        get
        {
            string query = CommandPaletteQuery.Trim();
            return query.Length == 0
                ? CommandPaletteItems
                : CommandPaletteItems.Where(item => item.Matches(query));
        }
    }

    private void InitializeProductivity()
    {
        DesktopProductivityState state = _productivityStateStore
            .LoadAsync()
            .GetAwaiter()
            .GetResult();
        ApplyProductivityState(state);
        CanUndoHistoryRemoval = _downloadManager.UndoableRemovalCount > 0;
        RefreshNotifications();
        BuildCommandPalette();
    }

    private void BuildCommandPalette()
    {
        CommandPaletteItems.Clear();
        CommandPaletteItems.Add(new("new-download", "Add a new download", "Focus the URL field", "Ctrl+N"));
        CommandPaletteItems.Add(new("search-downloads", "Search downloads", "Focus advanced search", "Ctrl+F"));
        CommandPaletteItems.Add(new("pause-selected", "Pause selected downloads", "Uses the current multi-selection", string.Empty));
        CommandPaletteItems.Add(new("resume-selected", "Resume selected downloads", "Uses the current multi-selection", string.Empty));
        CommandPaletteItems.Add(new("undo-remove", "Undo removed history", "Restore the most recently removed history entry", "Ctrl+Z"));
        CommandPaletteItems.Add(new("notifications", "Open notification center", "Review recent completed and failed tasks", string.Empty));
        CommandPaletteItems.Add(new("diagnostics", "Open diagnostics", "Inspect transfer and subsystem health", string.Empty));
        CommandPaletteItems.Add(new("bandwidth-unlimited", "Set bandwidth to unlimited", "Remove the global transfer limit", string.Empty));
        CommandPaletteItems.Add(new("bandwidth-1m", "Set bandwidth to 1 MiB/s", "Apply a global transfer limit", string.Empty));
        CommandPaletteItems.Add(new("bandwidth-5m", "Set bandwidth to 5 MiB/s", "Apply a global transfer limit", string.Empty));
        CommandPaletteItems.Add(new("compact-window", "Open compact mini-window", "Show active count, speed, and controls", string.Empty));
    }

    public async Task ExecuteCommandPaletteItemAsync(CommandPaletteItemViewModel item)
    {
        ArgumentNullException.ThrowIfNull(item);
        IsCommandPaletteOpen = false;
        CommandPaletteQuery = string.Empty;
        switch (item.Id)
        {
            case "new-download":
                SelectSection("downloads");
                UiActionRequested?.Invoke(this, "focus-new-download");
                break;
            case "search-downloads":
                SelectSection("downloads");
                UiActionRequested?.Invoke(this, "focus-download-search");
                break;
            case "pause-selected":
                await PauseBulkAsync();
                break;
            case "resume-selected":
                await ResumeBulkAsync();
                break;
            case "undo-remove":
                await UndoHistoryRemovalAsync();
                break;
            case "notifications":
                IsNotificationCenterOpen = true;
                UiActionRequested?.Invoke(this, "open-notifications");
                break;
            case "diagnostics":
                SelectSection("diagnostics");
                break;
            case "bandwidth-unlimited":
                await ApplyQuickBandwidthLimitAsync(0);
                break;
            case "bandwidth-1m":
                await ApplyQuickBandwidthLimitAsync(1024 * 1024);
                break;
            case "bandwidth-5m":
                await ApplyQuickBandwidthLimitAsync(5 * 1024 * 1024);
                break;
            case "compact-window":
                UiActionRequested?.Invoke(this, "open-mini-window");
                break;
        }
    }

    public async Task ApplyQuickBandwidthLimitAsync(long bytesPerSecond)
    {
        long normalizedLimit = Math.Max(0, bytesPerSecond);
        await _settingsService.UpdateAsync(_settingsService.Current with
        {
            DefaultSpeedLimitBytesPerSecond = normalizedLimit
        });
        DefaultSpeedLimitKbps = normalizedLimit <= 0
            ? "0"
            : (normalizedLimit / 1024).ToString(System.Globalization.CultureInfo.InvariantCulture);
        OperationMessage = normalizedLimit <= 0
            ? "Global bandwidth limit disabled."
            : $"Global bandwidth limited to {normalizedLimit / 1024 / 1024d:0.#} MiB/s.";
    }

    [RelayCommand]
    private void ClearNotifications()
        => _notificationCenter.Clear();

    [RelayCommand]
    private void DismissNotification(NotificationCenterEntryViewModel? entry)
    {
        if (entry is not null)
        {
            _notificationCenter.Dismiss(entry.Id);
        }
    }

    [RelayCommand]
    private async Task UndoHistoryRemovalAsync()
    {
        string? restoredId = await _downloadManager.UndoLastRemovalAsync();
        CanUndoHistoryRemoval = _downloadManager.UndoableRemovalCount > 0;
        OperationMessage = restoredId is null
            ? "There is no removed history entry to restore."
            : "Removed download history restored.";
    }

    [RelayCommand]
    private async Task ToggleSelectedDownloadNotificationsAsync()
    {
        if (SelectedDownload is null)
        {
            return;
        }

        if (_mutedDownloadNotifications.Remove(SelectedDownload.Id))
        {
            SelectedDownloadNotificationsEnabled = true;
            OperationMessage = $"Desktop notifications enabled for {SelectedDownload.FileName}.";
        }
        else
        {
            _mutedDownloadNotifications.Add(SelectedDownload.Id);
            SelectedDownloadNotificationsEnabled = false;
            OperationMessage = $"Desktop notifications muted for {SelectedDownload.FileName}.";
        }

        await PersistProductivityStateAsync();
    }

    [RelayCommand]
    private async Task AddClipboardReviewAsync()
    {
        if (string.IsNullOrWhiteSpace(ClipboardReviewText))
        {
            return;
        }

        NewDownloadUrls = ClipboardReviewText;
        IsClipboardReviewVisible = false;
        ClipboardReviewSummary = string.Empty;
        ClipboardReviewText = string.Empty;
        SelectSection("downloads");
        UiActionRequested?.Invoke(this, "focus-new-download");
        if (AutoAddClipboardLinks)
        {
            await AddDownloadAsync();
        }
    }

    [RelayCommand]
    private void DismissClipboardReview()
    {
        IsClipboardReviewVisible = false;
        ClipboardReviewSummary = string.Empty;
        ClipboardReviewText = string.Empty;
    }

    [RelayCommand]
    private void ToggleDownloadDensity()
        => DownloadDensity = DownloadDensity == DownloadListDensity.Compact
            ? DownloadListDensity.Comfortable
            : DownloadListDensity.Compact;

    public void SetClipboardReview(IReadOnlyList<Uri> urls)
    {
        ArgumentNullException.ThrowIfNull(urls);
        if (urls.Count == 0)
        {
            return;
        }

        ClipboardReviewText = string.Join(Environment.NewLine, urls.Select(static url => url.AbsoluteUri));
        ClipboardReviewSummary = urls.Count == 1
            ? $"Review clipboard link: {urls[0].Host}"
            : $"Review {urls.Count} links captured from the clipboard.";
        IsClipboardReviewVisible = true;
    }

    public bool ShouldShowDesktopNotification(string downloadId)
        => !_mutedDownloadNotifications.Contains(downloadId);

    public void SetDownloadDetailsPaneWidth(double width)
    {
        DownloadDetailsPaneWidth = Math.Clamp(width, 260, 760);
    }

    private void RefreshNotifications()
    {
        Notifications.Clear();
        foreach (NotificationCenterEntry entry in _notificationCenter.Snapshot())
        {
            Notifications.Add(new NotificationCenterEntryViewModel(entry));
        }

        OnPropertyChanged(nameof(HasNotifications));
        OnPropertyChanged(nameof(NotificationCount));
    }

    private void RefreshSelectedDownloadNotificationState()
        => SelectedDownloadNotificationsEnabled = SelectedDownload is null
            || !_mutedDownloadNotifications.Contains(SelectedDownload.Id);

    private void OnNotificationCenterChanged(object? sender, EventArgs eventArgs)
    {
        if (_dispatcher.CheckAccess())
        {
            RefreshNotifications();
        }
        else
        {
            _dispatcher.Post(RefreshNotifications);
        }
    }

    partial void OnCommandPaletteQueryChanged(string value)
        => OnPropertyChanged(nameof(FilteredCommandPaletteItems));

    partial void OnDownloadDensityChanged(DownloadListDensity value)
    {
        OnPropertyChanged(nameof(IsCompactDownloadDensity));
        OnPropertyChanged(nameof(DownloadItemPadding));
        QueueProductivityStateSave();
    }

    partial void OnDownloadDetailsPaneWidthChanged(double value)
        => QueueProductivityStateSave();

    partial void OnShowDownloadSourceChanged(bool value)
        => QueueProductivityStateSave();

    partial void OnShowDownloadQueueChanged(bool value)
        => QueueProductivityStateSave();

    partial void OnShowDownloadPriorityChanged(bool value)
        => QueueProductivityStateSave();

    partial void OnShowDownloadTagsChanged(bool value)
        => QueueProductivityStateSave();

    partial void OnShowDownloadSpeedChanged(bool value)
        => QueueProductivityStateSave();

    partial void OnShowDownloadRemainingChanged(bool value)
        => QueueProductivityStateSave();

    private void ApplyProductivityState(DesktopProductivityState state)
    {
        _applyingProductivityState = true;
        try
        {
            DownloadDensity = state.DownloadDensity;
            DownloadDetailsPaneWidth = state.DownloadDetailsWidth;
            ShowDownloadSource = state.ShowSource;
            ShowDownloadQueue = state.ShowQueue;
            ShowDownloadPriority = state.ShowPriority;
            ShowDownloadTags = state.ShowTags;
            ShowDownloadSpeed = state.ShowSpeed;
            ShowDownloadRemaining = state.ShowRemaining;
            _mutedDownloadNotifications.Clear();
            foreach (string id in state.MutedDownloadIds)
            {
                _mutedDownloadNotifications.Add(id);
            }
            RefreshSelectedDownloadNotificationState();
        }
        finally
        {
            _applyingProductivityState = false;
        }
    }

    private void QueueProductivityStateSave()
    {
        if (!_applyingProductivityState)
        {
            _ = PersistProductivityStateAsync();
        }
    }

    private async Task PersistProductivityStateAsync()
    {
        try
        {
            await _productivityStateStore.SaveAsync(new DesktopProductivityState(
                DownloadDensity,
                DownloadDetailsPaneWidth,
                ShowDownloadSource,
                ShowDownloadQueue,
                ShowDownloadPriority,
                ShowDownloadTags,
                ShowDownloadSpeed,
                ShowDownloadRemaining,
                _mutedDownloadNotifications.Order(StringComparer.Ordinal).ToArray()));
        }
        catch (IOException)
        {
            // UI preferences are best-effort and must not interrupt download operations.
        }
        catch (UnauthorizedAccessException)
        {
            // UI preferences are best-effort and must not interrupt download operations.
        }
        catch (ObjectDisposedException)
        {
            // The application may be shutting down while a queued preference save completes.
        }
    }

    public Task HandleDroppedTextAsync(string text)
    {
        IReadOnlyList<Uri> urls = DownloadInputParser.ParseUrls(text);
        if (urls.Count == 0)
        {
            OperationMessage = "The dropped text did not contain an HTTP or HTTPS URL.";
            return Task.CompletedTask;
        }

        NewDownloadUrls = string.Join(Environment.NewLine, urls.Select(static url => url.AbsoluteUri));
        SelectSection("downloads");
        UiActionRequested?.Invoke(this, "focus-new-download");
        OperationMessage = $"Added {urls.Count} dropped URL{(urls.Count == 1 ? string.Empty : "s")} to the review form.";
        return Task.CompletedTask;
    }

    public async Task HandleDroppedFilesAsync(IReadOnlyList<string> paths)
    {
        ArgumentNullException.ThrowIfNull(paths);
        int imported = 0;
        List<string> urls = [];
        foreach (string path in paths.Where(static path => !string.IsNullOrWhiteSpace(path)))
        {
            string extension = Path.GetExtension(path);
            if (extension.Equals(".meta4", StringComparison.OrdinalIgnoreCase)
                || extension.Equals(".metalink", StringComparison.OrdinalIgnoreCase))
            {
                await ImportMetalinkAsync(path);
                imported++;
                continue;
            }

            if (extension.Equals(".json", StringComparison.OrdinalIgnoreCase))
            {
                HistoryTransferPath = path;
                await ImportDownloadListAsync();
                imported++;
                continue;
            }

            if (extension.Equals(".txt", StringComparison.OrdinalIgnoreCase)
                || extension.Equals(".url", StringComparison.OrdinalIgnoreCase))
            {
                try
                {
                    string text = await File.ReadAllTextAsync(path);
                    if (extension.Equals(".url", StringComparison.OrdinalIgnoreCase))
                    {
                        text = string.Join(
                            Environment.NewLine,
                            text.Split(['\r', '\n'], StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
                                .Where(static line => line.StartsWith("URL=", StringComparison.OrdinalIgnoreCase))
                                .Select(static line => line[4..].Trim()));
                    }

                    urls.AddRange(DownloadInputParser.ParseUrls(text).Select(static url => url.AbsoluteUri));
                    imported++;
                }
                catch (IOException exception)
                {
                    OperationMessage = exception.Message;
                }
                catch (UnauthorizedAccessException exception)
                {
                    OperationMessage = exception.Message;
                }
            }
        }

        if (urls.Count > 0)
        {
            NewDownloadUrls = string.Join(Environment.NewLine, urls.Distinct(StringComparer.Ordinal));
            SelectSection("downloads");
            UiActionRequested?.Invoke(this, "focus-new-download");
        }

        OperationMessage = imported > 0
            ? $"Processed {imported} dropped file{(imported == 1 ? string.Empty : "s")}."
            : "No supported Metalink, JSON, text, or internet-shortcut files were dropped.";
    }
}

public sealed record NotificationCenterEntryViewModel(NotificationCenterEntry Entry)
{
    public string Id => Entry.Id;

    public string Title => Entry.Title;

    public string Message => Entry.Message;

    public string Severity => Entry.Severity.ToString();

    public string TimestampText => Entry.Timestamp.ToLocalTime().ToString("g", System.Globalization.CultureInfo.CurrentCulture);

    public string? DownloadId => Entry.DownloadId;
}

public sealed record CommandPaletteItemViewModel(
    string Id,
    string Title,
    string Description,
    string Shortcut)
{
    public bool Matches(string query)
        => Title.Contains(query, StringComparison.OrdinalIgnoreCase)
            || Description.Contains(query, StringComparison.OrdinalIgnoreCase)
            || Id.Contains(query, StringComparison.OrdinalIgnoreCase);
}
