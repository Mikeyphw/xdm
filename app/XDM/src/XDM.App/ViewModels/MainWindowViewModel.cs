using System.Collections.ObjectModel;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using XDM.BrowserIntegration;
using XDM.Core.Abstractions;
using XDM.Core.Downloads;
using XDM.Core.Queues;
using XDM.Core.Settings;
using XDM.Core.State;
using XDM.Diagnostics;
using XDM.DownloadEngine;
using XDM.Media;
using XDM.Platform;

namespace XDM.App.ViewModels;

public partial class MainWindowViewModel : ObservableObject, IDisposable
{
    private readonly IApplicationState _applicationState;
    private readonly IDownloadManager _downloadManager;
    private readonly ISettingsService _settingsService;
    private readonly IUiDispatcher _dispatcher;
    private readonly IBrowserIntegrationService _browserIntegrationService;
    private readonly IMediaProbeService _mediaProbeService;
    private readonly IMediaCatalogService _mediaCatalogService;
    private readonly IMediaDownloadService _mediaDownloadService;
    private readonly IFfmpegService _ffmpegService;
    private readonly IYtDlpProvider _ytDlpProvider;
    private readonly IDiagnosticEventStore _diagnosticEvents;
    private readonly IDiagnosticBundleService _diagnosticBundleService;
    private readonly IDesktopNotificationService _desktopNotifications;
    private readonly IBrowserHostInstaller _browserHostInstaller;
    private readonly Dictionary<string, DownloadState> _lastDownloadStates = new(StringComparer.Ordinal);
    private readonly Dictionary<string, List<DownloadTimelineEntry>> _downloadTimelines = new(StringComparer.Ordinal);
    private CancellationTokenSource? _mediaDownloadCancellation;
    private MediaCatalog? _currentMediaCatalog;
    private bool _disposed;

    public MainWindowViewModel(
        IApplicationState applicationState,
        IPlatformInfo platformInfo,
        IDownloadManager downloadManager,
        ISettingsService settingsService,
        IUiDispatcher dispatcher,
        IBrowserIntegrationService browserIntegrationService,
        IMediaProbeService mediaProbeService,
        IMediaCatalogService mediaCatalogService,
        IMediaDownloadService mediaDownloadService,
        IFfmpegService ffmpegService,
        IYtDlpProvider ytDlpProvider,
        IDiagnosticEventStore diagnosticEvents,
        IDiagnosticBundleService diagnosticBundleService,
        IRecoveryService recoveryService,
        IDesktopNotificationService desktopNotifications,
        IBrowserHostInstaller browserHostInstaller)
    {
        _applicationState = applicationState;
        _downloadManager = downloadManager;
        _settingsService = settingsService;
        _dispatcher = dispatcher;
        _browserIntegrationService = browserIntegrationService;
        _mediaProbeService = mediaProbeService;
        _mediaCatalogService = mediaCatalogService;
        _mediaDownloadService = mediaDownloadService;
        _ffmpegService = ffmpegService;
        _ytDlpProvider = ytDlpProvider;
        _diagnosticEvents = diagnosticEvents;
        _diagnosticBundleService = diagnosticBundleService;
        _desktopNotifications = desktopNotifications;
        _browserHostInstaller = browserHostInstaller;
        PlatformDescription = platformInfo.DisplayName;
        RuntimeDescription = platformInfo.Runtime;

        Sections = new ObservableCollection<NavigationItem>
        {
            new("Downloads", "↓", "Batch downloads, request metadata, live progress, and history."),
            new("Queues", "≡", "Queue definitions, concurrency, and per-queue bandwidth policies."),
            new("Scheduler", "◷", "Time windows for unattended queue runs."),
            new("Browser Integration", "◉", "Extension, native host, and capture health."),
            new("Media", "▶", "HLS, DASH, yt-dlp discovery, formats, subtitles, and live capture."),
            new("Settings", "⚙", "Folders, limits, clipboard monitoring, and behavior."),
            new("Diagnostics", "◇", "Startup, runtime, browser, and engine diagnostics.")
        };

        DuplicateBehaviors = Enum.GetNames<DuplicateFileBehavior>();
        SelectedSection = Sections[0];
        ApplySettings(settingsService.Current);
        ApplySnapshot(applicationState.Current);
        ApplyQueueRuntime(downloadManager.QueueRuntime);
        ApplyBrowserStatus(browserIntegrationService.Current);
        BrowserHostStatus = browserHostInstaller.GetStatus().Message;
        ApplyDiagnostics();
        RecoveryStatus = recoveryService.SafeMode
            ? "Safe mode is active; browser integration and scheduler startup were skipped."
            : recoveryService.PreviousSessionWasUnclean
                ? "The previous session did not shut down cleanly."
                : "No recovery action is currently required.";
        applicationState.Changed += OnApplicationStateChanged;
        settingsService.Changed += OnSettingsChanged;
        downloadManager.QueueRuntimeChanged += OnQueueRuntimeChanged;
        browserIntegrationService.StatusChanged += OnBrowserStatusChanged;
        browserIntegrationService.CaptureReceived += OnBrowserCaptureReceived;
        diagnosticEvents.Changed += OnDiagnosticsChanged;
    }

    public ObservableCollection<NavigationItem> Sections { get; }

    public ObservableCollection<DownloadItemViewModel> Downloads { get; } = [];

    public ObservableCollection<DownloadItemViewModel> FilteredDownloads { get; } = [];

    public ObservableCollection<DownloadCategoryDefinition> CategoryDefinitions { get; } = [];

    public ObservableCollection<DownloadQueueDefinition> QueueDefinitions { get; } = [];

    public ObservableCollection<DiagnosticEvent> DiagnosticEvents { get; } = [];

    public ObservableCollection<DownloadTimelineEntry> SelectedDownloadTimeline { get; } = [];

    public ObservableCollection<MediaFormatViewModel> MediaVideoFormats { get; } = [];

    public ObservableCollection<MediaFormatViewModel> MediaAudioFormats { get; } = [];

    public ObservableCollection<MediaFormatViewModel> MediaSubtitleFormats { get; } = [];

    public IReadOnlyList<string> DuplicateBehaviors { get; }

    public IReadOnlyList<string> DownloadStatusFilters { get; } =
        ["All", "Queued", "Connecting", "Downloading", "Paused", "Finalizing", "Completed", "Failed", "Cancelled"];

    public string PlatformDescription { get; }

    public string RuntimeDescription { get; }

    [ObservableProperty]
    private NavigationItem? selectedSection;

    [ObservableProperty]
    private DownloadItemViewModel? selectedDownload;

    [ObservableProperty]
    private string downloadSearchText = string.Empty;

    [ObservableProperty]
    private string selectedDownloadStatus = "All";

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

    [ObservableProperty]
    private string browserIntegrationStatus = "Stopped";

    [ObservableProperty]
    private string browserEndpoint = "http://127.0.0.1:9614";

    [ObservableProperty]
    private string browserAuthenticationToken = string.Empty;

    [ObservableProperty]
    private string browserExtensionId = string.Empty;

    [ObservableProperty]
    private string browserHostStatus = "Native host status has not been checked.";

    [ObservableProperty]
    private string lastBrowserCapture = "No browser captures yet";

    [ObservableProperty]
    private string lastMediaProbe = "No media probe yet";

    [ObservableProperty]
    private string mediaSourceUrl = string.Empty;

    [ObservableProperty]
    private string mediaDestinationFile = "video.mp4";

    [ObservableProperty]
    private string mediaCatalogSummary = "Enter a media URL or supported page, then discover formats.";

    [ObservableProperty]
    private MediaFormatViewModel? selectedMediaVideoFormat;

    [ObservableProperty]
    private MediaFormatViewModel? selectedMediaAudioFormat;

    [ObservableProperty]
    private string mediaLiveDurationMinutes = "10";

    [ObservableProperty]
    private string mediaDownloadStatus = "No media download is active.";

    [ObservableProperty]
    private double mediaDownloadProgress;

    [ObservableProperty]
    private bool mediaDownloadIndeterminate;

    [ObservableProperty]
    private bool mediaDownloadActive;

    [ObservableProperty]
    private string mediaToolHealth = "Tool health has not been checked.";

    [ObservableProperty]
    private string recoveryStatus = "Checking recovery state";

    [ObservableProperty]
    private string diagnosticBundlePath = "No diagnostic bundle exported yet";

    public bool IsSelectedQueueActive
        => SelectedQueue is not null && _downloadManager.QueueRuntime.IsActive(SelectedQueue.Id);

    public bool IsDownloadsVisible
        => string.Equals(SelectedSection?.Title, "Downloads", StringComparison.Ordinal);

    public bool IsQueuesVisible
        => string.Equals(SelectedSection?.Title, "Queues", StringComparison.Ordinal);

    public bool IsSchedulerVisible
        => string.Equals(SelectedSection?.Title, "Scheduler", StringComparison.Ordinal);

    public bool IsBrowserIntegrationVisible
        => string.Equals(SelectedSection?.Title, "Browser Integration", StringComparison.Ordinal);

    public bool IsMediaVisible
        => string.Equals(SelectedSection?.Title, "Media", StringComparison.Ordinal);

    public bool IsSettingsVisible
        => string.Equals(SelectedSection?.Title, "Settings", StringComparison.Ordinal);

    public bool IsDiagnosticsVisible
        => string.Equals(SelectedSection?.Title, "Diagnostics", StringComparison.Ordinal);

    public bool IsPlaceholderVisible
        => !IsDownloadsVisible
            && !IsQueuesVisible
            && !IsSchedulerVisible
            && !IsBrowserIntegrationVisible
            && !IsMediaVisible
            && !IsSettingsVisible
            && !IsDiagnosticsVisible;

    partial void OnSelectedSectionChanged(NavigationItem? value)
    {
        CurrentTitle = value?.Title ?? "Downloads";
        CurrentSummary = value?.Summary ?? string.Empty;
        OnPropertyChanged(nameof(IsDownloadsVisible));
        OnPropertyChanged(nameof(IsQueuesVisible));
        OnPropertyChanged(nameof(IsSchedulerVisible));
        OnPropertyChanged(nameof(IsBrowserIntegrationVisible));
        OnPropertyChanged(nameof(IsMediaVisible));
        OnPropertyChanged(nameof(IsSettingsVisible));
        OnPropertyChanged(nameof(IsDiagnosticsVisible));
        OnPropertyChanged(nameof(IsPlaceholderVisible));
    }


    partial void OnDownloadSearchTextChanged(string value)
        => RefreshFilteredDownloads();

    partial void OnSelectedDownloadStatusChanged(string value)
        => RefreshFilteredDownloads();

    partial void OnSelectedDownloadChanged(DownloadItemViewModel? value)
        => RefreshSelectedDownloadTimeline(value?.Id);

    partial void OnSelectedMediaVideoFormatChanged(MediaFormatViewModel? value)
    {
        if (value?.Format.StreamKind == MediaStreamKind.Muxed)
        {
            SelectedMediaAudioFormat = null;
        }
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
    private void SelectAllVisible()
    {
        foreach (DownloadItemViewModel download in FilteredDownloads)
        {
            download.IsSelected = true;
        }

        OperationMessage = $"Selected {FilteredDownloads.Count} visible download(s).";
    }

    [RelayCommand]
    private void ClearBulkSelection()
    {
        foreach (DownloadItemViewModel download in Downloads)
        {
            download.IsSelected = false;
        }

        OperationMessage = "Bulk selection cleared.";
    }

    [RelayCommand]
    private async Task PauseBulkAsync()
    {
        DownloadItemViewModel[] targets = GetActionTargets();
        foreach (DownloadItemViewModel download in targets.Where(static item => item.CanPause))
        {
            await _downloadManager.PauseAsync(download.Id);
        }

        OperationMessage = $"Pause requested for {targets.Length} download(s).";
    }

    [RelayCommand]
    private async Task ResumeBulkAsync()
    {
        DownloadItemViewModel[] targets = GetActionTargets();
        foreach (DownloadItemViewModel download in targets.Where(static item => item.CanResume))
        {
            await _downloadManager.ResumeAsync(download.Id);
        }

        OperationMessage = $"Resume requested for {targets.Length} download(s).";
    }

    [RelayCommand]
    private async Task CancelBulkAsync()
    {
        DownloadItemViewModel[] targets = GetActionTargets();
        foreach (DownloadItemViewModel download in targets.Where(static item => item.CanCancel))
        {
            await _downloadManager.CancelAsync(download.Id);
        }

        OperationMessage = $"Cancel requested for {targets.Length} download(s).";
    }

    [RelayCommand]
    private async Task RemoveBulkAsync()
    {
        DownloadItemViewModel[] targets = GetActionTargets();
        foreach (DownloadItemViewModel download in targets)
        {
            await _downloadManager.RemoveAsync(download.Id);
        }

        OperationMessage = $"Removed {targets.Length} download(s) from history.";
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
            ?? (QueueDefinitions.Count > 0 ? QueueDefinitions[0].Id : "default");

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
    private async Task RestartBrowserIntegrationAsync()
    {
        await _browserIntegrationService.StopAsync();
        await _browserIntegrationService.InitializeAsync();
        ApplyBrowserStatus(_browserIntegrationService.Current);
    }

    [RelayCommand]
    private async Task RepairBrowserHostAsync()
    {
        try
        {
            BrowserHostInstallationStatus status = await _browserHostInstaller
                .RepairAsync(EmptyToNull(BrowserExtensionId));
            BrowserHostStatus = status.Message;
            OperationMessage = status.NativeHostExists
                ? "Browser native-host registration repaired."
                : "Native-host executable is not present in the application directory.";
        }
        catch (IOException exception)
        {
            BrowserHostStatus = $"Repair failed: {exception.Message}";
            OperationMessage = BrowserHostStatus;
        }
        catch (UnauthorizedAccessException exception)
        {
            BrowserHostStatus = $"Repair failed: {exception.Message}";
            OperationMessage = BrowserHostStatus;
        }
        catch (InvalidDataException exception)
        {
            BrowserHostStatus = $"Repair failed: {exception.Message}";
            OperationMessage = BrowserHostStatus;
        }
    }

    [RelayCommand]
    private async Task UninstallBrowserHostAsync()
    {
        try
        {
            BrowserHostInstallationStatus status = await _browserHostInstaller.UninstallAsync();
            BrowserHostStatus = status.Message;
            OperationMessage = "Browser native-host registrations removed.";
        }
        catch (IOException exception)
        {
            BrowserHostStatus = $"Uninstall failed: {exception.Message}";
            OperationMessage = BrowserHostStatus;
        }
        catch (UnauthorizedAccessException exception)
        {
            BrowserHostStatus = $"Uninstall failed: {exception.Message}";
            OperationMessage = BrowserHostStatus;
        }
    }

    [RelayCommand]
    private async Task ProbeEnteredUrlAsync()
    {
        IReadOnlyList<Uri> enteredUrls = DownloadInputParser.ParseUrls(NewDownloadUrls);
        Uri? source = enteredUrls.Count > 0 ? enteredUrls[0] : null;
        if (source is null)
        {
            LastMediaProbe = "Enter a valid HTTP or HTTPS URL first.";
            return;
        }

        try
        {
            MediaProbeResult result = await _mediaProbeService.ProbeAsync(source);
            LastMediaProbe = FormatMediaProbe(result);
        }
        catch (HttpRequestException exception)
        {
            LastMediaProbe = $"Probe failed: {exception.Message}";
        }
        catch (InvalidOperationException exception)
        {
            LastMediaProbe = $"Probe failed: {exception.Message}";
        }
    }

    [RelayCommand]
    private async Task ProbeMediaAsync()
    {
        IReadOnlyList<Uri> enteredUrls = DownloadInputParser.ParseUrls(NewDownloadUrls);
        string sourceText = string.IsNullOrWhiteSpace(MediaSourceUrl)
            ? enteredUrls.Count > 0 ? enteredUrls[0].AbsoluteUri : string.Empty
            : MediaSourceUrl.Trim();
        if (!Uri.TryCreate(sourceText, UriKind.Absolute, out Uri? source)
            || source.Scheme is not ("http" or "https"))
        {
            MediaCatalogSummary = "Enter a valid HTTP or HTTPS media URL.";
            return;
        }

        MediaSourceUrl = source.AbsoluteUri;
        MediaCatalogSummary = "Discovering native and provider formats…";
        try
        {
            MediaCatalog catalog = await _mediaCatalogService.GetCatalogAsync(
                source,
                BuildMediaMetadata(),
                CancellationToken.None);
            ApplyMediaCatalog(catalog);
        }
        catch (HttpRequestException exception)
        {
            MediaCatalogSummary = $"Media discovery failed: {exception.Message}";
        }
        catch (InvalidDataException exception)
        {
            MediaCatalogSummary = $"Media discovery failed: {exception.Message}";
        }
        catch (InvalidOperationException exception)
        {
            MediaCatalogSummary = $"Media discovery failed: {exception.Message}";
        }
        catch (NotSupportedException exception)
        {
            MediaCatalogSummary = $"Media discovery failed: {exception.Message}";
        }
        catch (UnauthorizedAccessException exception)
        {
            MediaCatalogSummary = $"Media discovery failed: {exception.Message}";
        }
    }

    [RelayCommand]
    private async Task DownloadMediaAsync()
    {
        if (MediaDownloadActive)
        {
            return;
        }

        if (!Uri.TryCreate(MediaSourceUrl.Trim(), UriKind.Absolute, out Uri? source)
            || source.Scheme is not ("http" or "https"))
        {
            MediaDownloadStatus = "Discover a valid media URL first.";
            return;
        }

        string fileName = string.IsNullOrWhiteSpace(MediaDestinationFile)
            ? "video.mp4"
            : Path.GetFileName(MediaDestinationFile.Trim());
        string destination = Path.IsPathRooted(MediaDestinationFile)
            ? MediaDestinationFile
            : Path.Combine(DestinationFolder, fileName);
        TimeSpan? liveDuration = _currentMediaCatalog?.IsLive == true
            ? ParseLiveDuration(MediaLiveDurationMinutes)
            : null;
        if (_currentMediaCatalog?.IsLive == true && liveDuration is null)
        {
            MediaDownloadStatus = "Enter a live capture duration between 1 and 10080 minutes.";
            return;
        }

        string[] subtitleIds = MediaSubtitleFormats
            .Where(static format => format.IsSelected)
            .Select(static format => format.Id)
            .ToArray();
        MediaDownloadRequest request = new(
            source,
            destination,
            SelectedMediaVideoFormat?.Id,
            SelectedMediaAudioFormat?.Id,
            subtitleIds,
            liveDuration,
            BuildMediaMetadata());
        _mediaDownloadCancellation?.Dispose();
        _mediaDownloadCancellation = new CancellationTokenSource();
        MediaDownloadActive = true;
        MediaDownloadIndeterminate = true;
        MediaDownloadProgress = 0;
        MediaDownloadStatus = "Preparing media download…";
        Progress<MediaDownloadProgress> progress = new(OnMediaProgress);
        try
        {
            MediaDownloadResult result = await _mediaDownloadService.DownloadAsync(
                request,
                progress,
                _mediaDownloadCancellation.Token);
            MediaDownloadStatus = $"Completed • {result.DownloadedFragments} fragment(s) • {FormatBytes(result.DownloadedBytes)} • {result.DestinationPath}";
            MediaDownloadProgress = 1;
            MediaDownloadIndeterminate = false;
            await _desktopNotifications.ShowAsync("Media download completed", Path.GetFileName(result.DestinationPath));
        }
        catch (OperationCanceledException)
        {
            MediaDownloadStatus = "Media download cancelled. Checkpoints were preserved for resume.";
        }
        catch (HttpRequestException exception)
        {
            MediaDownloadStatus = $"Media download failed: {exception.Message}";
        }
        catch (IOException exception)
        {
            MediaDownloadStatus = $"Media download failed: {exception.Message}";
        }
        catch (InvalidDataException exception)
        {
            MediaDownloadStatus = $"Media download failed: {exception.Message}";
        }
        catch (InvalidOperationException exception)
        {
            MediaDownloadStatus = $"Media download failed: {exception.Message}";
        }
        catch (NotSupportedException exception)
        {
            MediaDownloadStatus = $"Media download failed: {exception.Message}";
        }
        catch (UnauthorizedAccessException exception)
        {
            MediaDownloadStatus = $"Media download failed: {exception.Message}";
        }
        finally
        {
            MediaDownloadActive = false;
            MediaDownloadIndeterminate = false;
            _mediaDownloadCancellation?.Dispose();
            _mediaDownloadCancellation = null;
        }
    }

    [RelayCommand]
    private void CancelMediaDownload()
        => _mediaDownloadCancellation?.Cancel();

    [RelayCommand]
    private async Task RefreshMediaToolHealthAsync()
    {
        MediaToolHealth = "Checking FFmpeg and yt-dlp…";
        Task<ExternalToolHealth> ffmpegTask = _ffmpegService.GetHealthAsync();
        Task<ExternalToolHealth> ytDlpTask = _ytDlpProvider.GetHealthAsync();
        await Task.WhenAll(ffmpegTask, ytDlpTask);
        ExternalToolHealth ffmpeg = await ffmpegTask;
        ExternalToolHealth ytDlp = await ytDlpTask;
        MediaToolHealth = $"FFmpeg: {(ffmpeg.IsAvailable ? ffmpeg.Version ?? "available" : ffmpeg.Message)} • yt-dlp: {(ytDlp.IsAvailable ? ytDlp.Version ?? "available" : ytDlp.Message)}";
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

    [RelayCommand]
    private async Task ExportDiagnosticsAsync()
    {
        try
        {
            string destination = string.IsNullOrWhiteSpace(DestinationFolder)
                ? _settingsService.Current.DefaultDownloadDirectory
                : DestinationFolder;
            DiagnosticBundlePath = await _diagnosticBundleService
                .ExportAsync(destination);
            _diagnosticEvents.Record(
                DiagnosticSeverity.Information,
                "XDM-DIAGNOSTICS-EXPORT",
                $"Diagnostic bundle exported to {DiagnosticBundlePath}.");
            OperationMessage = "Diagnostic bundle exported.";
        }
        catch (IOException exception)
        {
            DiagnosticBundlePath = $"Export failed: {exception.Message}";
            _diagnosticEvents.Record(
                DiagnosticSeverity.Error,
                "XDM-DIAGNOSTICS-EXPORT",
                DiagnosticBundlePath);
        }
        catch (UnauthorizedAccessException exception)
        {
            DiagnosticBundlePath = $"Export failed: {exception.Message}";
            _diagnosticEvents.Record(
                DiagnosticSeverity.Error,
                "XDM-DIAGNOSTICS-EXPORT",
                DiagnosticBundlePath);
        }
    }

    [RelayCommand]
    private void ClearDiagnostics()
    {
        _diagnosticEvents.Clear();
        OperationMessage = "Diagnostic event history cleared.";
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
        _mediaDownloadCancellation?.Cancel();
        _mediaDownloadCancellation?.Dispose();
        _applicationState.Changed -= OnApplicationStateChanged;
        _settingsService.Changed -= OnSettingsChanged;
        _downloadManager.QueueRuntimeChanged -= OnQueueRuntimeChanged;
        _browserIntegrationService.StatusChanged -= OnBrowserStatusChanged;
        _browserIntegrationService.CaptureReceived -= OnBrowserCaptureReceived;
        _diagnosticEvents.Changed -= OnDiagnosticsChanged;
        GC.SuppressFinalize(this);
    }

    private void OnDiagnosticsChanged(object? sender, EventArgs eventArgs)
    {
        if (_dispatcher.CheckAccess())
        {
            ApplyDiagnostics();
        }
        else
        {
            _dispatcher.Post(ApplyDiagnostics);
        }
    }

    private void ApplyDiagnostics()
    {
        DiagnosticEvents.Clear();
        IReadOnlyList<DiagnosticEvent> snapshot = _diagnosticEvents.Snapshot();
        int startIndex = Math.Max(0, snapshot.Count - 100);
        for (int index = snapshot.Count - 1; index >= startIndex; index--)
        {
            DiagnosticEvents.Add(snapshot[index]);
        }
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

    private void OnBrowserStatusChanged(object? sender, BrowserStatusChangedEventArgs eventArgs)
    {
        _diagnosticEvents.Record(
            eventArgs.Status.LastError is null ? DiagnosticSeverity.Information : DiagnosticSeverity.Warning,
            "XDM-BROWSER-STATUS",
            eventArgs.Status.LastError ?? (eventArgs.Status.IsListening ? "Browser integration is listening." : "Browser integration stopped."));
        if (_dispatcher.CheckAccess())
        {
            ApplyBrowserStatus(eventArgs.Status);
        }
        else
        {
            _dispatcher.Post(() => ApplyBrowserStatus(eventArgs.Status));
        }
    }

    private void OnBrowserCaptureReceived(object? sender, BrowserCaptureEventArgs eventArgs)
        => _ = HandleBrowserCaptureAsync(eventArgs);

    private async Task HandleBrowserCaptureAsync(BrowserCaptureEventArgs eventArgs)
    {
        BrowserCaptureRequest request = eventArgs.Request;
        if (request.Method == "GET" && request.Operation == "media")
        {
            try
            {
                MediaCatalog catalog = await _mediaCatalogService.GetCatalogAsync(
                    request.Url,
                    new MediaRequestMetadata(request.Headers, request.Cookie, request.Referer, request.UserAgent))
                    .ConfigureAwait(false);
                eventArgs.Accept($"media-{Guid.NewGuid():N}");
                _dispatcher.Post(() =>
                {
                    MediaSourceUrl = request.Url.AbsoluteUri;
                    ApplyMediaCatalog(catalog);
                    SelectedSection = Sections.FirstOrDefault(static section => section.Title == "Media") ?? SelectedSection;
                    OperationMessage = $"Media formats discovered from {request.Browser ?? "browser"}.";
                });
            }
            catch (HttpRequestException exception)
            {
                eventArgs.Reject(exception.Message);
                SetBrowserCaptureFailure(exception.Message);
            }
            catch (InvalidDataException exception)
            {
                eventArgs.Reject(exception.Message);
                SetBrowserCaptureFailure(exception.Message);
            }
            catch (InvalidOperationException exception)
            {
                eventArgs.Reject(exception.Message);
                SetBrowserCaptureFailure(exception.Message);
            }
            catch (NotSupportedException exception)
            {
                eventArgs.Reject(exception.Message);
                SetBrowserCaptureFailure(exception.Message);
            }

            return;
        }

        string downloadId;
        try
        {
            ApplicationSettings settings = _settingsService.Current;
            DownloadRequest downloadRequest = new(
                request.Url,
                settings.DefaultDownloadDirectory,
                request.FileName,
                request.Headers,
                Cookie: request.Cookie,
                Referer: request.Referer,
                UserAgent: request.UserAgent,
                QueueId: request.QueueId,
                CategoryId: request.CategoryId,
                ConnectionCount: request.Method == "GET" ? 4 : 1,
                Method: request.Method,
                RequestBody: request.GetRequestBody(),
                RequestBodyContentType: request.RequestBodyContentType);
            downloadId = await _downloadManager.AddAsync(downloadRequest).ConfigureAwait(false);
            eventArgs.Accept(downloadId);
        }
        catch (IOException exception)
        {
            eventArgs.Reject(exception.Message);
            SetBrowserCaptureFailure(exception.Message);
            return;
        }
        catch (ArgumentException exception)
        {
            eventArgs.Reject(exception.Message);
            SetBrowserCaptureFailure(exception.Message);
            return;
        }
        catch (InvalidOperationException exception)
        {
            eventArgs.Reject(exception.Message);
            SetBrowserCaptureFailure(exception.Message);
            return;
        }
        catch (UnauthorizedAccessException exception)
        {
            eventArgs.Reject(exception.Message);
            SetBrowserCaptureFailure(exception.Message);
            return;
        }

        MediaProbeResult? probe = null;
        if (request.Method == "GET")
        {
            try
            {
                probe = await _mediaProbeService.ProbeAsync(request.Url).ConfigureAwait(false);
            }
            catch (HttpRequestException)
            {
            }
            catch (InvalidOperationException)
            {
            }
        }

        _dispatcher.Post(() =>
        {
            LastBrowserCapture = $"{request.Browser ?? "Browser"}: {request.Url.GetLeftPart(UriPartial.Path)}";
            if (probe is not null)
            {
                LastMediaProbe = FormatMediaProbe(probe);
            }

            OperationMessage = $"Browser capture accepted as {downloadId}.";
        });
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
            bool stateChanged = !_lastDownloadStates.TryGetValue(download.Id, out DownloadState previousState)
                || previousState != download.State;
            _lastDownloadStates[download.Id] = download.State;

            if (existing.Remove(download.Id, out DownloadItemViewModel? item))
            {
                item.Apply(download);
            }
            else
            {
                Downloads.Add(new DownloadItemViewModel(download));
            }

            if (stateChanged)
            {
                AppendTimeline(download);
                if (download.State == DownloadState.Completed)
                {
                    _ = _desktopNotifications.ShowAsync("Download completed", download.FileName);
                }
                else if (download.State == DownloadState.Failed)
                {
                    _ = _desktopNotifications.ShowAsync("Download failed", download.FileName);
                }
            }
        }

        foreach (DownloadItemViewModel removed in existing.Values)
        {
            Downloads.Remove(removed);
        }

        RefreshFilteredDownloads();
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

    private void ApplyBrowserStatus(BrowserIntegrationStatus status)
    {
        BrowserIntegrationStatus = status.IsListening
            ? $"Listening • protocol {status.ProtocolVersion}"
            : status.LastError is null
                ? "Stopped"
                : $"Unavailable: {status.LastError}";
        BrowserEndpoint = $"{status.Endpoint}/capture";
        BrowserAuthenticationToken = RedactToken(status.AuthenticationToken);
        if (status.LastCapturedUrl is not null)
        {
            LastBrowserCapture = $"{status.LastBrowser ?? "Browser"}: {status.LastCapturedUrl}";
        }
    }

    private static string RedactToken(string token)
        => token.Length <= 8 ? "••••••••" : $"{token[..8]}…{token[^4..]}";

    private void SetBrowserCaptureFailure(string message)
        => _dispatcher.Post(() =>
        {
            OperationMessage = $"Browser capture failed: {message}";
            LastBrowserCapture = OperationMessage;
        });

    private static string FormatMediaProbe(MediaProbeResult result)
        => result.IsMedia
            ? $"{result.Kind} • {result.VariantCount} stream/variant(s) • {result.Description}"
            : result.Description;

    private void ApplyMediaCatalog(MediaCatalog catalog)
    {
        _currentMediaCatalog = catalog;
        MediaVideoFormats.Clear();
        MediaAudioFormats.Clear();
        MediaSubtitleFormats.Clear();
        foreach (MediaFormat format in catalog.Formats)
        {
            MediaFormatViewModel viewModel = new(format);
            switch (format.StreamKind)
            {
                case MediaStreamKind.Muxed:
                case MediaStreamKind.Video:
                    MediaVideoFormats.Add(viewModel);
                    break;
                case MediaStreamKind.Audio:
                    MediaAudioFormats.Add(viewModel);
                    break;
                case MediaStreamKind.Subtitle:
                    viewModel.IsSelected = format.IsDefault;
                    MediaSubtitleFormats.Add(viewModel);
                    break;
            }
        }

        SelectedMediaVideoFormat = MediaVideoFormats
            .OrderByDescending(static format => format.Format.IsDefault)
            .ThenByDescending(static format => format.Format.Height ?? 0)
            .ThenByDescending(static format => format.Format.Bandwidth ?? 0)
            .FirstOrDefault();
        SelectedMediaAudioFormat = SelectedMediaVideoFormat?.Format.StreamKind == MediaStreamKind.Muxed
            ? null
            : MediaAudioFormats
                .OrderByDescending(static format => format.Format.IsDefault)
                .ThenByDescending(static format => format.Format.Bandwidth ?? 0)
                .FirstOrDefault();
        MediaCatalogSummary = $"{catalog.Title} • {catalog.Kind} • {(catalog.IsLive ? "live" : "VOD")} • {catalog.Formats.Count} format(s) • {catalog.Provider}";
        if (string.IsNullOrWhiteSpace(MediaDestinationFile) || MediaDestinationFile == "video.mp4")
        {
            string safeTitle = SanitizeMediaFileName(catalog.Title);
            MediaDestinationFile = $"{safeTitle}.mp4";
        }
    }

    private void OnMediaProgress(MediaDownloadProgress progress)
    {
        MediaDownloadStatus = $"{progress.Stage} • {progress.Message} • {FormatBytes(progress.DownloadedBytes)}";
        if (progress.Fraction is double fraction)
        {
            MediaDownloadProgress = fraction;
            MediaDownloadIndeterminate = false;
        }
        else
        {
            MediaDownloadIndeterminate = true;
        }
    }

    private MediaRequestMetadata BuildMediaMetadata()
        => new(
            DownloadInputParser.ParseHeaders(RequestHeaders),
            EmptyToNull(Cookie),
            EmptyToNull(Referer),
            EmptyToNull(UserAgent));

    private static TimeSpan? ParseLiveDuration(string value)
        => double.TryParse(
            value,
            System.Globalization.NumberStyles.Float,
            System.Globalization.CultureInfo.InvariantCulture,
            out double minutes)
            && minutes is >= 1 and <= 10080
                ? TimeSpan.FromMinutes(minutes)
                : null;

    private static string SanitizeMediaFileName(string value)
    {
        HashSet<char> invalid = new(Path.GetInvalidFileNameChars());
        string sanitized = new string(value.Select(character => invalid.Contains(character) ? '_' : character).ToArray()).Trim();
        return sanitized.Length == 0 ? "video" : sanitized;
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
            ?? (CategoryDefinitions.Count > 0 ? CategoryDefinitions[0] : null);
        SelectedQueue = QueueDefinitions.FirstOrDefault(queue =>
            string.Equals(queue.Id, selectedQueueId, StringComparison.Ordinal))
            ?? (QueueDefinitions.Count > 0 ? QueueDefinitions[0] : null);
        SchedulerQueue = QueueDefinitions.FirstOrDefault(queue =>
            string.Equals(queue.Id, settings.Scheduler.QueueId, StringComparison.Ordinal))
            ?? (QueueDefinitions.Count > 0 ? QueueDefinitions[0] : null);
        ApplyQueueRuntime(_downloadManager.QueueRuntime);
    }


    private DownloadItemViewModel[] GetActionTargets()
    {
        DownloadItemViewModel[] checkedDownloads = Downloads
            .Where(static download => download.IsSelected)
            .ToArray();
        if (checkedDownloads.Length > 0)
        {
            return checkedDownloads;
        }

        return SelectedDownload is null ? [] : [SelectedDownload];
    }

    private void AppendTimeline(DownloadSnapshot download)
    {
        if (!_downloadTimelines.TryGetValue(download.Id, out List<DownloadTimelineEntry>? entries))
        {
            entries = [];
            _downloadTimelines.Add(download.Id, entries);
        }

        string message = download.ErrorMessage is null
            ? download.DownloadedBytes.ToString("N0", System.Globalization.CultureInfo.InvariantCulture) + " bytes received."
            : download.ErrorMessage;
        entries.Add(new DownloadTimelineEntry(DateTimeOffset.Now, download.State.ToString(), message));
        if (entries.Count > 100)
        {
            entries.RemoveRange(0, entries.Count - 100);
        }

        if (string.Equals(SelectedDownload?.Id, download.Id, StringComparison.Ordinal))
        {
            RefreshSelectedDownloadTimeline(download.Id);
        }
    }

    private void RefreshSelectedDownloadTimeline(string? downloadId)
    {
        SelectedDownloadTimeline.Clear();
        if (downloadId is null || !_downloadTimelines.TryGetValue(downloadId, out List<DownloadTimelineEntry>? entries))
        {
            return;
        }

        foreach (DownloadTimelineEntry entry in entries.AsEnumerable().Reverse())
        {
            SelectedDownloadTimeline.Add(entry);
        }
    }

    private void RefreshFilteredDownloads()
    {
        string search = DownloadSearchText.Trim();
        string status = SelectedDownloadStatus;
        FilteredDownloads.Clear();
        foreach (DownloadItemViewModel download in Downloads)
        {
            bool statusMatches = string.Equals(status, "All", StringComparison.OrdinalIgnoreCase)
                || string.Equals(download.StatusText, status, StringComparison.OrdinalIgnoreCase);
            bool searchMatches = search.Length == 0
                || download.FileName.Contains(search, StringComparison.OrdinalIgnoreCase)
                || download.Source.AbsoluteUri.Contains(search, StringComparison.OrdinalIgnoreCase)
                || download.DestinationPath.Contains(search, StringComparison.OrdinalIgnoreCase)
                || download.QueueId.Contains(search, StringComparison.OrdinalIgnoreCase);
            if (statusMatches && searchMatches)
            {
                FilteredDownloads.Add(download);
            }
        }

        if (SelectedDownload is not null && !FilteredDownloads.Contains(SelectedDownload))
        {
            SelectedDownload = FilteredDownloads.Count > 0 ? FilteredDownloads[0] : null;
        }
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

    private static string FormatBytes(long bytes)
    {
        string[] units = ["B", "KB", "MB", "GB", "TB"];
        double value = Math.Max(0, bytes);
        int unitIndex = 0;
        while (value >= 1024 && unitIndex < units.Length - 1)
        {
            value /= 1024;
            unitIndex++;
        }

        return $"{value:0.#} {units[unitIndex]}";
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
