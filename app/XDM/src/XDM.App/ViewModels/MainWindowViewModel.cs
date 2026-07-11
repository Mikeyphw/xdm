using System.Collections.ObjectModel;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using XDM.Core.Abstractions;
using XDM.Core.Downloads;
using XDM.Core.Queues;
using XDM.Core.Settings;
using XDM.Core.State;
using XDM.DownloadEngine;
using XDM.Platform;

namespace XDM.App.ViewModels;

public partial class MainWindowViewModel : ObservableObject, IDisposable
{
    private readonly IApplicationState _applicationState;
    private readonly IDownloadManager _downloadManager;
    private readonly ISettingsService _settingsService;
    private readonly IUiDispatcher _dispatcher;
    private bool _disposed;

    public MainWindowViewModel(
        IApplicationState applicationState,
        IPlatformInfo platformInfo,
        IDownloadManager downloadManager,
        ISettingsService settingsService,
        IUiDispatcher dispatcher)
    {
        _applicationState = applicationState;
        _downloadManager = downloadManager;
        _settingsService = settingsService;
        _dispatcher = dispatcher;
        PlatformDescription = platformInfo.DisplayName;
        RuntimeDescription = platformInfo.Runtime;

        Sections = new ObservableCollection<NavigationItem>
        {
            new("Downloads", "↓", "Batch downloads, request metadata, live progress, and history."),
            new("Queues", "≡", "Queue definitions, concurrency, and per-queue bandwidth policies."),
            new("Scheduler", "◷", "Time windows for unattended queue runs."),
            new("Browser Integration", "◉", "Extension, native host, and capture health."),
            new("Settings", "⚙", "Folders, limits, clipboard monitoring, and behavior."),
            new("Diagnostics", "◇", "Startup, runtime, browser, and engine diagnostics.")
        };

        DuplicateBehaviors = Enum.GetNames<DuplicateFileBehavior>();
        SelectedSection = Sections[0];
        ApplySettings(settingsService.Current);
        ApplySnapshot(applicationState.Current);
        ApplyQueueRuntime(downloadManager.QueueRuntime);
        applicationState.Changed += OnApplicationStateChanged;
        settingsService.Changed += OnSettingsChanged;
        downloadManager.QueueRuntimeChanged += OnQueueRuntimeChanged;
    }

    public ObservableCollection<NavigationItem> Sections { get; }

    public ObservableCollection<DownloadItemViewModel> Downloads { get; } = [];

    public ObservableCollection<DownloadCategoryDefinition> CategoryDefinitions { get; } = [];

    public ObservableCollection<DownloadQueueDefinition> QueueDefinitions { get; } = [];

    public IReadOnlyList<string> DuplicateBehaviors { get; }

    public string PlatformDescription { get; }

    public string RuntimeDescription { get; }

    [ObservableProperty]
    private NavigationItem? selectedSection;

    [ObservableProperty]
    private DownloadItemViewModel? selectedDownload;

    [ObservableProperty]
    private DownloadCategoryDefinition? selectedCategory;

    [ObservableProperty]
    private DownloadQueueDefinition? selectedQueue;

    [ObservableProperty]
    private string currentTitle = "Downloads";

    [ObservableProperty]
    private string currentSummary = "Batch downloads, request metadata, live progress, and history.";

    [ObservableProperty]
    private string coreStatus = "Starting";

    [ObservableProperty]
    private int activeDownloadCount;

    [ObservableProperty]
    private string aggregateSpeed = "0 B/s";

    [ObservableProperty]
    private string newDownloadUrls = string.Empty;

    [ObservableProperty]
    private string destinationFolder = string.Empty;

    [ObservableProperty]
    private string customFileName = string.Empty;

    [ObservableProperty]
    private string requestHeaders = string.Empty;

    [ObservableProperty]
    private string username = string.Empty;

    [ObservableProperty]
    private string password = string.Empty;

    [ObservableProperty]
    private string cookie = string.Empty;

    [ObservableProperty]
    private string referer = string.Empty;

    [ObservableProperty]
    private string userAgent = string.Empty;

    [ObservableProperty]
    private string selectedDuplicateBehavior = nameof(DuplicateFileBehavior.AutoRename);

    [ObservableProperty]
    private string speedLimitKbps = string.Empty;

    [ObservableProperty]
    private string operationMessage = "Ready";

    [ObservableProperty]
    private string maxConcurrentDownloads = "4";

    [ObservableProperty]
    private string defaultSpeedLimitKbps = "0";

    [ObservableProperty]
    private bool clipboardMonitoringEnabled;

    [ObservableProperty]
    private bool autoAddClipboardLinks;

    [ObservableProperty]
    private string newQueueName = string.Empty;

    [ObservableProperty]
    private string newCategoryName = string.Empty;

    [ObservableProperty]
    private string newCategoryExtensions = string.Empty;

    [ObservableProperty]
    private string newCategoryDestination = string.Empty;

    [ObservableProperty]
    private bool schedulerEnabled;

    [ObservableProperty]
    private string schedulerStartTime = "00:00";

    [ObservableProperty]
    private string schedulerEndTime = "23:59";

    [ObservableProperty]
    private DownloadQueueDefinition? schedulerQueue;

    [ObservableProperty]
    private string queueRuntimeStatus = "No active queues";

    public bool IsSelectedQueueActive
        => SelectedQueue is not null && _downloadManager.QueueRuntime.IsActive(SelectedQueue.Id);

    public bool IsDownloadsVisible
        => string.Equals(SelectedSection?.Title, "Downloads", StringComparison.Ordinal);

    public bool IsQueuesVisible
        => string.Equals(SelectedSection?.Title, "Queues", StringComparison.Ordinal);

    public bool IsSchedulerVisible
        => string.Equals(SelectedSection?.Title, "Scheduler", StringComparison.Ordinal);

    public bool IsSettingsVisible
        => string.Equals(SelectedSection?.Title, "Settings", StringComparison.Ordinal);

    public bool IsPlaceholderVisible
        => !IsDownloadsVisible && !IsQueuesVisible && !IsSchedulerVisible && !IsSettingsVisible;

    partial void OnSelectedSectionChanged(NavigationItem? value)
    {
        CurrentTitle = value?.Title ?? "Downloads";
        CurrentSummary = value?.Summary ?? string.Empty;
        OnPropertyChanged(nameof(IsDownloadsVisible));
        OnPropertyChanged(nameof(IsQueuesVisible));
        OnPropertyChanged(nameof(IsSchedulerVisible));
        OnPropertyChanged(nameof(IsSettingsVisible));
        OnPropertyChanged(nameof(IsPlaceholderVisible));
    }

    partial void OnSelectedQueueChanged(DownloadQueueDefinition? value)
    {
        OnPropertyChanged(nameof(IsSelectedQueueActive));
    }

    partial void OnSelectedCategoryChanged(DownloadCategoryDefinition? value)
    {
        if (value is not null && !string.IsNullOrWhiteSpace(value.DestinationDirectory))
        {
            DestinationFolder = value.DestinationDirectory;
        }
    }

    [RelayCommand]
    private async Task AddDownloadAsync()
    {
        IReadOnlyList<Uri> sources = DownloadInputParser.ParseUrls(NewDownloadUrls);
        if (sources.Count == 0)
        {
            OperationMessage = "Enter at least one valid HTTP or HTTPS URL.";
            return;
        }

        if (string.IsNullOrWhiteSpace(DestinationFolder))
        {
            OperationMessage = "Choose a destination folder.";
            return;
        }

        DuplicateFileBehavior duplicateBehavior = Enum.TryParse(
            SelectedDuplicateBehavior,
            ignoreCase: true,
            out DuplicateFileBehavior parsedBehavior)
                ? parsedBehavior
                : DuplicateFileBehavior.AutoRename;
        IReadOnlyDictionary<string, string> headers = DownloadInputParser.ParseHeaders(RequestHeaders);
        long? speedLimit = ParseKilobytesPerSecond(SpeedLimitKbps);
        int added = 0;
        List<string> failures = [];

        foreach (Uri source in sources)
        {
            try
            {
                string? fileName = sources.Count == 1 && !string.IsNullOrWhiteSpace(CustomFileName)
                    ? CustomFileName.Trim()
                    : null;
                DownloadRequest request = new(
                    source,
                    DestinationFolder,
                    fileName,
                    headers,
                    EmptyToNull(Username),
                    EmptyToNull(Password),
                    EmptyToNull(Cookie),
                    EmptyToNull(Referer),
                    EmptyToNull(UserAgent),
                    SelectedQueue?.Id,
                    SelectedCategory?.Id,
                    speedLimit,
                    duplicateBehavior);

                await _downloadManager.AddAsync(request);
                added++;
            }
            catch (ArgumentException exception)
            {
                failures.Add(exception.Message);
            }
            catch (IOException exception)
            {
                failures.Add(exception.Message);
            }
            catch (UnauthorizedAccessException exception)
            {
                failures.Add(exception.Message);
            }
        }

        if (added > 0)
        {
            NewDownloadUrls = string.Empty;
            CustomFileName = string.Empty;
        }

        OperationMessage = failures.Count == 0
            ? $"Added {added} download{(added == 1 ? string.Empty : "s")}."
            : $"Added {added}; {failures.Count} failed: {failures[0]}";
    }

    [RelayCommand]
    private Task PauseSelectedAsync()
        => SelectedDownload is null
            ? Task.CompletedTask
            : _downloadManager.PauseAsync(SelectedDownload.Id);

    [RelayCommand]
    private Task ResumeSelectedAsync()
        => SelectedDownload is null
            ? Task.CompletedTask
            : _downloadManager.ResumeAsync(SelectedDownload.Id);

    [RelayCommand]
    private Task CancelSelectedAsync()
        => SelectedDownload is null
            ? Task.CompletedTask
            : _downloadManager.CancelAsync(SelectedDownload.Id);

    [RelayCommand]
    private Task RetrySelectedAsync()
        => SelectedDownload is null
            ? Task.CompletedTask
            : _downloadManager.RetryAsync(SelectedDownload.Id);

    [RelayCommand]
    private async Task RemoveSelectedAsync()
    {
        if (SelectedDownload is null)
        {
            return;
        }

        string id = SelectedDownload.Id;
        await _downloadManager.RemoveAsync(id);
        OperationMessage = "Download removed from history.";
    }

    [RelayCommand]
    private async Task SaveSettingsAsync()
    {
        ApplicationSettings current = _settingsService.Current;
        int concurrency = int.TryParse(
            MaxConcurrentDownloads,
            System.Globalization.NumberStyles.Integer,
            System.Globalization.CultureInfo.InvariantCulture,
            out int parsedConcurrency)
                ? parsedConcurrency
                : current.MaxConcurrentDownloads;
        long defaultSpeed = ParseKilobytesPerSecond(DefaultSpeedLimitKbps) ?? 0;
        TimeOnly startTime = TimeOnly.TryParseExact(
            SchedulerStartTime,
            "HH:mm",
            System.Globalization.CultureInfo.InvariantCulture,
            System.Globalization.DateTimeStyles.None,
            out TimeOnly parsedStart)
                ? parsedStart
                : current.Scheduler.StartTime;
        TimeOnly endTime = TimeOnly.TryParseExact(
            SchedulerEndTime,
            "HH:mm",
            System.Globalization.CultureInfo.InvariantCulture,
            System.Globalization.DateTimeStyles.None,
            out TimeOnly parsedEnd)
                ? parsedEnd
                : current.Scheduler.EndTime;
        string schedulerQueueId = SchedulerQueue?.Id
            ?? QueueDefinitions.FirstOrDefault()?.Id
            ?? "default";

        ApplicationSettings updated = current with
        {
            DefaultDownloadDirectory = DestinationFolder,
            MaxConcurrentDownloads = concurrency,
            DefaultSpeedLimitBytesPerSecond = defaultSpeed,
            ClipboardMonitoringEnabled = ClipboardMonitoringEnabled,
            AutoAddClipboardLinks = AutoAddClipboardLinks,
            Categories = CategoryDefinitions.ToArray(),
            Queues = QueueDefinitions.ToArray(),
            Scheduler = current.Scheduler with
            {
                Enabled = SchedulerEnabled,
                QueueId = schedulerQueueId,
                StartTime = startTime,
                EndTime = endTime
            }
        };

        await _settingsService.UpdateAsync(updated);
        OperationMessage = "Settings saved. Concurrency changes apply after restart.";
    }

    [RelayCommand]
    private void AddQueue()
    {
        string name = NewQueueName.Trim();
        if (name.Length == 0)
        {
            OperationMessage = "Enter a queue name.";
            return;
        }

        string id = CreateStableId(name, QueueDefinitions.Select(static queue => queue.Id));
        QueueDefinitions.Add(new DownloadQueueDefinition(id, name, 2, 0));
        SelectedQueue = QueueDefinitions[^1];
        SchedulerQueue ??= SelectedQueue;
        NewQueueName = string.Empty;
        OperationMessage = "Queue added; save settings to persist it.";
    }

    [RelayCommand]
    private async Task StartSelectedQueueAsync()
    {
        if (SelectedQueue is null)
        {
            OperationMessage = "Select a queue first.";
            return;
        }

        await _downloadManager.StartQueueAsync(SelectedQueue.Id);
        OperationMessage = $"Queue '{SelectedQueue.Name}' started.";
    }

    [RelayCommand]
    private async Task StopSelectedQueueAsync()
    {
        if (SelectedQueue is null)
        {
            OperationMessage = "Select a queue first.";
            return;
        }

        await _downloadManager.StopQueueAsync(SelectedQueue.Id);
        OperationMessage = $"Queue '{SelectedQueue.Name}' stopped.";
    }

    [RelayCommand]
    private async Task MoveSelectedDownloadToQueueAsync()
    {
        if (SelectedDownload is null || SelectedQueue is null)
        {
            OperationMessage = "Select both a download and a queue.";
            return;
        }

        await _downloadManager.MoveToQueueAsync(SelectedDownload.Id, SelectedQueue.Id);
        OperationMessage = $"Moved '{SelectedDownload.FileName}' to '{SelectedQueue.Name}'.";
    }

    [RelayCommand]
    private async Task MoveSelectedDownloadEarlierAsync()
    {
        if (SelectedDownload is null)
        {
            return;
        }

        await _downloadManager.MoveToQueueAsync(
            SelectedDownload.Id,
            SelectedDownload.QueueId,
            Math.Max(0, SelectedDownload.QueueOrder - 1));
    }

    [RelayCommand]
    private async Task MoveSelectedDownloadLaterAsync()
    {
        if (SelectedDownload is null)
        {
            return;
        }

        await _downloadManager.MoveToQueueAsync(
            SelectedDownload.Id,
            SelectedDownload.QueueId,
            SelectedDownload.QueueOrder + 1);
    }

    [RelayCommand]
    private void AddCategory()
    {
        string name = NewCategoryName.Trim();
        if (name.Length == 0)
        {
            OperationMessage = "Enter a category name.";
            return;
        }

        string id = CreateStableId(name, CategoryDefinitions.Select(static category => category.Id));
        string[] extensions = NewCategoryExtensions
            .Split([',', ';', ' '], StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
        string destination = string.IsNullOrWhiteSpace(NewCategoryDestination)
            ? DestinationFolder
            : NewCategoryDestination;
        CategoryDefinitions.Add(new DownloadCategoryDefinition(id, name, extensions, destination));
        SelectedCategory = CategoryDefinitions[^1];
        NewCategoryName = string.Empty;
        NewCategoryExtensions = string.Empty;
        NewCategoryDestination = string.Empty;
        OperationMessage = "Category added; save settings to persist it.";
    }

    public async Task HandleClipboardTextAsync(string text)
    {
        if (!ClipboardMonitoringEnabled)
        {
            return;
        }

        IReadOnlyList<Uri> urls = DownloadInputParser.ParseUrls(text);
        if (urls.Count == 0)
        {
            return;
        }

        NewDownloadUrls = string.Join(Environment.NewLine, urls.Select(static uri => uri.AbsoluteUri));
        OperationMessage = $"Captured {urls.Count} URL{(urls.Count == 1 ? string.Empty : "s")} from the clipboard.";
        if (AutoAddClipboardLinks)
        {
            await AddDownloadAsync();
        }
    }

    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }

        _disposed = true;
        _applicationState.Changed -= OnApplicationStateChanged;
        _settingsService.Changed -= OnSettingsChanged;
        _downloadManager.QueueRuntimeChanged -= OnQueueRuntimeChanged;
        GC.SuppressFinalize(this);
    }

    private void OnApplicationStateChanged(object? sender, ApplicationSnapshot snapshot)
    {
        if (_dispatcher.CheckAccess())
        {
            ApplySnapshot(snapshot);
        }
        else
        {
            _dispatcher.Post(() => ApplySnapshot(snapshot));
        }
    }

    private void OnQueueRuntimeChanged(object? sender, QueueRuntimeSnapshot snapshot)
    {
        if (_dispatcher.CheckAccess())
        {
            ApplyQueueRuntime(snapshot);
        }
        else
        {
            _dispatcher.Post(() => ApplyQueueRuntime(snapshot));
        }
    }

    private void OnSettingsChanged(object? sender, ApplicationSettings settings)
    {
        if (_dispatcher.CheckAccess())
        {
            ApplySettings(settings);
        }
        else
        {
            _dispatcher.Post(() => ApplySettings(settings));
        }
    }

    private void ApplySnapshot(ApplicationSnapshot snapshot)
    {
        CoreStatus = snapshot.CoreReady ? "Ready" : "Starting";
        ActiveDownloadCount = snapshot.ActiveDownloadCount;
        AggregateSpeed = FormatSpeed(snapshot.AggregateBytesPerSecond);

        Dictionary<string, DownloadItemViewModel> existing = Downloads
            .ToDictionary(static item => item.Id, StringComparer.Ordinal);

        foreach (DownloadSnapshot download in snapshot.Downloads)
        {
            if (existing.Remove(download.Id, out DownloadItemViewModel? item))
            {
                item.Apply(download);
            }
            else
            {
                Downloads.Add(new DownloadItemViewModel(download));
            }
        }

        foreach (DownloadItemViewModel removed in existing.Values)
        {
            Downloads.Remove(removed);
        }
    }

    private void ApplyQueueRuntime(QueueRuntimeSnapshot snapshot)
    {
        string[] active = QueueDefinitions
            .Where(queue => snapshot.IsActive(queue.Id))
            .Select(queue => $"{queue.Name} ({snapshot.GetRunningCount(queue.Id)} running)")
            .ToArray();
        QueueRuntimeStatus = active.Length == 0
            ? "No active queues"
            : string.Join(" • ", active);
        OnPropertyChanged(nameof(IsSelectedQueueActive));
    }

    private void ApplySettings(ApplicationSettings settings)
    {
        DestinationFolder = settings.DefaultDownloadDirectory;
        MaxConcurrentDownloads = settings.MaxConcurrentDownloads.ToString(System.Globalization.CultureInfo.InvariantCulture);
        DefaultSpeedLimitKbps = (settings.DefaultSpeedLimitBytesPerSecond / 1024)
            .ToString(System.Globalization.CultureInfo.InvariantCulture);
        ClipboardMonitoringEnabled = settings.ClipboardMonitoringEnabled;
        AutoAddClipboardLinks = settings.AutoAddClipboardLinks;
        SchedulerEnabled = settings.Scheduler.Enabled;
        SchedulerStartTime = settings.Scheduler.StartTime.ToString("HH:mm", System.Globalization.CultureInfo.InvariantCulture);
        SchedulerEndTime = settings.Scheduler.EndTime.ToString("HH:mm", System.Globalization.CultureInfo.InvariantCulture);

        string? selectedCategoryId = SelectedCategory?.Id;
        string? selectedQueueId = SelectedQueue?.Id;
        CategoryDefinitions.Clear();
        foreach (DownloadCategoryDefinition category in settings.Categories)
        {
            CategoryDefinitions.Add(category);
        }

        QueueDefinitions.Clear();
        foreach (DownloadQueueDefinition queue in settings.Queues)
        {
            QueueDefinitions.Add(queue);
        }

        SelectedCategory = CategoryDefinitions.FirstOrDefault(category =>
            string.Equals(category.Id, selectedCategoryId, StringComparison.Ordinal))
            ?? CategoryDefinitions.FirstOrDefault();
        SelectedQueue = QueueDefinitions.FirstOrDefault(queue =>
            string.Equals(queue.Id, selectedQueueId, StringComparison.Ordinal))
            ?? QueueDefinitions.FirstOrDefault();
        SchedulerQueue = QueueDefinitions.FirstOrDefault(queue =>
            string.Equals(queue.Id, settings.Scheduler.QueueId, StringComparison.Ordinal))
            ?? QueueDefinitions.FirstOrDefault();
        ApplyQueueRuntime(_downloadManager.QueueRuntime);
    }

    private static long? ParseKilobytesPerSecond(string value)
    {
        if (!long.TryParse(
            value,
            System.Globalization.NumberStyles.Integer,
            System.Globalization.CultureInfo.InvariantCulture,
            out long kilobytes) || kilobytes <= 0)
        {
            return null;
        }

        return checked(kilobytes * 1024);
    }

    private static string? EmptyToNull(string value)
        => string.IsNullOrWhiteSpace(value) ? null : value.Trim();

    private static string CreateStableId(string name, IEnumerable<string> existingIds)
    {
        string stem = new(name
            .Trim()
            .ToUpperInvariant()
            .Select(character => char.IsLetterOrDigit(character) ? character : '-')
            .ToArray());
        stem = string.Join('-', stem.Split('-', StringSplitOptions.RemoveEmptyEntries));
        stem = stem.Length == 0 ? "item" : stem;
        HashSet<string> existing = new(existingIds, StringComparer.Ordinal);
        if (!existing.Contains(stem))
        {
            return stem;
        }

        for (int index = 2; index < 10_000; index++)
        {
            string candidate = $"{stem}-{index}";
            if (!existing.Contains(candidate))
            {
                return candidate;
            }
        }

        return $"{stem}-{Guid.NewGuid():N}";
    }

    private static string FormatSpeed(double bytesPerSecond)
    {
        string[] units = ["B/s", "KB/s", "MB/s", "GB/s"];
        int unitIndex = 0;

        while (bytesPerSecond >= 1024 && unitIndex < units.Length - 1)
        {
            bytesPerSecond /= 1024;
            unitIndex++;
        }

        return $"{bytesPerSecond:0.#} {units[unitIndex]}";
    }
}
