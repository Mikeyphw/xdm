using System.Globalization;
using System.Collections.ObjectModel;
using System.ComponentModel;
using XDM.App.Services;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using XDM.BrowserIntegration;
using XDM.Core.Abstractions;
using XDM.Core.Diagnostics;
using XDM.Core.Downloads;
using XDM.Core.Localization;
using XDM.Core.Policies;
using XDM.Core.Queues;
using XDM.Core.Scheduling;
using XDM.Core.Settings;
using XDM.Core.State;
using XDM.Diagnostics;
using XDM.DownloadEngine;
using XDM.DownloadEngine.Aria2;
using XDM.DownloadEngine.Queues;
using XDM.Media;
using XDM.Platform;

namespace XDM.App.ViewModels;

public partial class MainWindowViewModel : ObservableObject, IDisposable
{
    private static readonly string[] LineSeparators = ["\r\n", "\n"];
    private readonly IApplicationState _applicationState;
    private readonly IDownloadManager _downloadManager;
    private readonly IDownloadRecoveryCoordinator _downloadRecoveryCoordinator;
    private readonly IRecoveryService _recoveryService;
    private readonly ISettingsService _settingsService;
    private readonly LocalizationService _localization;
    private readonly IQueueSchedulerRuntime _queueSchedulerRuntime;
    private readonly ITransferPolicyRuntime _transferPolicyRuntime;
    private readonly ICompletionActionService _completionActionService;
    private readonly IUiDispatcher _dispatcher;
    private readonly IBrowserIntegrationService _browserIntegrationService;
    private readonly IMediaProbeService _mediaProbeService;
    private readonly IMediaCatalogService _mediaCatalogService;
    private readonly IMediaDownloadService _mediaDownloadService;
    private readonly IFfmpegService _ffmpegService;
    private readonly IYtDlpProvider _ytDlpProvider;
    private readonly IConversionService _conversionService;
    private readonly IConversionQueueService _conversionQueueService;
    private readonly IDiagnosticEventStore _diagnosticEvents;
    private readonly IDiagnosticBundleService _diagnosticBundleService;
    private readonly ITransferDiagnosticSource _transferDiagnosticSource;
    private readonly ITransferHealthProbe _transferHealthProbe;
    private readonly IDesktopNotificationService _desktopNotifications;
    private readonly IBrowserHostInstaller _browserHostInstaller;
    private readonly IApplicationLifetimeService _applicationLifetimeService;
    private readonly Dictionary<string, DownloadState> _lastDownloadStates = new(StringComparer.Ordinal);
    private readonly Dictionary<string, List<DownloadTimelineEntry>> _downloadTimelines = new(StringComparer.Ordinal);
    private CancellationTokenSource? _mediaDownloadCancellation;
    private MediaCatalog? _currentMediaCatalog;
    private bool _disposed;

    public MainWindowViewModel(
        IApplicationState applicationState,
        IPlatformInfo platformInfo,
        IDownloadManager downloadManager,
        IDownloadRecoveryCoordinator downloadRecoveryCoordinator,
        IAria2Service aria2Service,
        ISettingsService settingsService,
        LocalizationService localization,
        ISettingsTransferService settingsTransferService,
        XDM.Core.Persistence.IDownloadListTransferService downloadListTransferService,
        IPlatformService platformService,
        IQueueSchedulerRuntime queueSchedulerRuntime,
        ITransferPolicyRuntime transferPolicyRuntime,
        ICompletionActionService completionActionService,
        IUiDispatcher dispatcher,
        IBrowserIntegrationService browserIntegrationService,
        IMediaProbeService mediaProbeService,
        IMediaCatalogService mediaCatalogService,
        IMediaDownloadService mediaDownloadService,
        IFfmpegService ffmpegService,
        IYtDlpProvider ytDlpProvider,
        IConversionService conversionService,
        IConversionQueueService conversionQueueService,
        IDiagnosticEventStore diagnosticEvents,
        IDiagnosticBundleService diagnosticBundleService,
        ITransferDiagnosticSource transferDiagnosticSource,
        ITransferHealthProbe transferHealthProbe,
        ISubsystemHealthService subsystemHealthService,
        IDeterministicDownloadTestService deterministicDownloadTestService,
        IRecoveryService recoveryService,
        IDesktopNotificationService desktopNotifications,
        INotificationCenterService notificationCenter,
        DesktopProductivityStateStore productivityStateStore,
        IBrowserHostInstaller browserHostInstaller,
        IApplicationLifetimeService applicationLifetimeService,
        IUpdateService updateService)
    {
        _applicationState = applicationState;
        _downloadManager = downloadManager;
        _downloadRecoveryCoordinator = downloadRecoveryCoordinator;
        _recoveryService = recoveryService;
        _aria2Service = aria2Service;
        _settingsService = settingsService;
        _localization = localization;
        _settingsTransferService = settingsTransferService;
        _downloadListTransferService = downloadListTransferService;
        _platformService = platformService;
        _queueSchedulerRuntime = queueSchedulerRuntime;
        _transferPolicyRuntime = transferPolicyRuntime;
        _completionActionService = completionActionService;
        _dispatcher = dispatcher;
        _browserIntegrationService = browserIntegrationService;
        _mediaProbeService = mediaProbeService;
        _mediaCatalogService = mediaCatalogService;
        _mediaDownloadService = mediaDownloadService;
        _ffmpegService = ffmpegService;
        _ytDlpProvider = ytDlpProvider;
        _conversionService = conversionService;
        _conversionQueueService = conversionQueueService;
        _diagnosticEvents = diagnosticEvents;
        _diagnosticBundleService = diagnosticBundleService;
        _transferDiagnosticSource = transferDiagnosticSource;
        _transferHealthProbe = transferHealthProbe;
        _subsystemHealthService = subsystemHealthService;
        _deterministicDownloadTestService = deterministicDownloadTestService;
        _desktopNotifications = desktopNotifications;
        _notificationCenter = notificationCenter;
        _productivityStateStore = productivityStateStore;
        _browserHostInstaller = browserHostInstaller;
        _applicationLifetimeService = applicationLifetimeService;
        _updateService = updateService;
        PlatformDescription = platformInfo.DisplayName;
        RuntimeDescription = platformInfo.Runtime;

        Localization = localization;
        Sections = new ObservableCollection<NavigationItem>
        {
            new("downloads", "nav_downloads", "M12 3V14.17L16.59 9.59L18 11L11 18L4 11L5.41 9.59L10 14.17V3H12M4 19H18V21H4V19Z", "nav_downloads_summary", localization),
            new("recovery", "nav_recovery", "M12 2A10 10 0 1 0 22 12H20A8 8 0 1 1 17.66 6.34L15 9H22V2L19.08 4.92A9.96 9.96 0 0 0 12 2Z", "nav_recovery_summary", localization),
            new("queues", "nav_queues", "M3 5H5V7H3V5M7 5H21V7H7V5M3 11H5V13H3V11M7 11H21V13H7V11M3 17H5V19H3V17M7 17H21V19H7V17Z", "nav_queues_summary", localization),
            new("scheduler", "nav_scheduler", "M12 2A10 10 0 1 0 22 12A10 10 0 0 0 12 2M13 7V11.59L16.2 14.79L14.79 16.2L11 12.41V7H13Z", "nav_scheduler_summary", localization),
            new("browser", "nav_browser", "M12 2A10 10 0 1 0 22 12A10 10 0 0 0 12 2M19.93 11H16.95A15.7 15.7 0 0 0 15.84 6.65A8.03 8.03 0 0 1 19.93 11M12 4C13.38 5.67 14.19 8.05 14.41 11H9.59C9.81 8.05 10.62 5.67 12 4M4.07 13H7.05C7.18 14.54 7.52 16 8.05 17.35A8.03 8.03 0 0 1 4.07 13M9.59 13H14.41C14.19 15.95 13.38 18.33 12 20C10.62 18.33 9.81 15.95 9.59 13M15.95 17.35C16.48 16 16.82 14.54 16.95 13H19.93A8.03 8.03 0 0 1 15.95 17.35M8.05 6.65C7.52 8 7.18 9.46 7.05 11H4.07A8.03 8.03 0 0 1 8.05 6.65Z", "nav_browser_summary", localization),
            new("media", "nav_media", "M8 5V19L19 12L8 5Z", "nav_media_summary", localization),
            new("conversion", "nav_conversion", "M7.5 21L3 16.5L7.5 12V15H13V18H7.5V21M16.5 12V9H11V6H16.5V3L21 7.5L16.5 12Z", "nav_conversion_summary", localization),
            new("settings", "nav_settings", "M12 8A4 4 0 1 0 16 12A4 4 0 0 0 12 8M3.05 13H1V11H3.05C3.2 10.28 3.48 9.6 3.87 9L2.42 7.55L3.84 6.13L5.29 7.58C5.9 7.19 6.58 6.91 7.3 6.76V4.71H9.3V6.76C10.02 6.91 10.7 7.19 11.31 7.58L12.76 6.13L14.18 7.55L12.73 9C13.12 9.6 13.4 10.28 13.55 11H15.6V13H13.55C13.4 13.72 13.12 14.4 12.73 15L14.18 16.45L12.76 17.87L11.31 16.42C10.7 16.81 10.02 17.09 9.3 17.24V19.29H7.3V17.24C6.58 17.09 5.9 16.81 5.29 16.42L3.84 17.87L2.42 16.45L3.87 15C3.48 14.4 3.2 13.72 3.05 13Z", "nav_settings_summary", localization),
            new("diagnostics", "nav_diagnostics", "M3 13H7L9 8L13 18L15 13H21V11H16.35L13 4L9 14L8 11H3V13Z", "nav_diagnostics_summary", localization)
        };

        DuplicateBehaviors = Enum.GetNames<DuplicateFileBehavior>();
        RefreshDownloadStatusFilters();
        SelectedSection = Sections[0];
        ApplySettings(settingsService.Current);
        ApplyAria2Snapshot(aria2Service.Current);
        ApplySnapshot(applicationState.Current);
        ApplyQueueRuntime(downloadManager.QueueRuntime);
        ApplySchedulerRuntime(queueSchedulerRuntime.Current);
        ApplyTransferPolicy(transferPolicyRuntime.Current);
        CompletionCapabilitySummary = FormatCompletionCapabilities(completionActionService.GetCapabilities());
        ApplyBrowserStatus(browserIntegrationService.Current);
        BrowserHostStatus = browserHostInstaller.GetStatus().Message;
        foreach (ConversionPreset preset in conversionService.Presets)
        {
            ConversionPresets.Add(preset);
        }

        SelectedConversionPreset = ConversionPresets.Count > 0 ? ConversionPresets[0] : null;
        MediaPostConversionPreset = ConversionPresets.FirstOrDefault(
            static preset => string.Equals(preset.Id, "mp4-h264-balanced", StringComparison.Ordinal));
        ApplyConversionQueue(conversionQueueService.Current);
        ApplyDiagnostics();
        ApplySelectedTransferDiagnostics();
        ApplyHealthProbeResult(transferHealthProbe.LastResult);
        ApplySubsystemHealth(subsystemHealthService.Current);
        if (deterministicDownloadTestService.LastResult is DeterministicDownloadTestResult testResult)
        {
            ApplyDeterministicDownloadTest(testResult);
        }
        PreviousSessionWasUnclean = recoveryService.PreviousSessionWasUnclean;
        OnPropertyChanged(nameof(ShowRecoveryReview));
        OnPropertyChanged(nameof(RecoveryReviewSummary));
        RecoveryStatus = recoveryService.SafeMode
            ? "Safe mode is active; browser integration and scheduler startup were skipped."
            : recoveryService.PreviousSessionWasUnclean
                ? "The previous session did not shut down cleanly. Review recovered downloads before resuming them."
                : "No recovery action is currently required.";
        ApplyRecoveryCandidates(downloadRecoveryCoordinator.Current);
        downloadRecoveryCoordinator.Changed += OnRecoveryCandidatesChanged;
        if (recoveryService.PreviousSessionWasUnclean)
        {
            SelectedSection = Sections.First(section => string.Equals(section.Id, "recovery", StringComparison.Ordinal));
        }
        applicationState.Changed += OnApplicationStateChanged;
        settingsService.Changed += OnSettingsChanged;
        localization.Changed += OnLocalizationChanged;
        downloadManager.QueueRuntimeChanged += OnQueueRuntimeChanged;
        queueSchedulerRuntime.Changed += OnSchedulerRuntimeChanged;
        transferPolicyRuntime.Changed += OnTransferPolicyChanged;
        browserIntegrationService.StatusChanged += OnBrowserStatusChanged;
        browserIntegrationService.CaptureReceived += OnBrowserCaptureReceived;
        diagnosticEvents.Changed += OnDiagnosticsChanged;
        transferDiagnosticSource.Changed += OnTransferDiagnosticsChanged;
        transferHealthProbe.Changed += OnTransferHealthProbeChanged;
        subsystemHealthService.Changed += OnSubsystemHealthChanged;
        deterministicDownloadTestService.Changed += OnDeterministicDownloadTestChanged;
        notificationCenter.Changed += OnNotificationCenterChanged;
        InitializeProductivity();
        conversionQueueService.Changed += OnConversionQueueChanged;
        aria2Service.Changed += OnAria2SnapshotChanged;
    }

    public LocalizationService Localization { get; }

    public IReadOnlyList<LanguageDefinition> AvailableLanguages => _localization.Languages;

    public ObservableCollection<NavigationItem> Sections { get; }

    public ObservableCollection<DownloadItemViewModel> Downloads { get; } = [];

    public ObservableCollection<DownloadItemViewModel> FilteredDownloads { get; } = [];

    public ObservableCollection<DownloadCategoryDefinition> CategoryDefinitions { get; } = [];

    public ObservableCollection<DownloadQueueDefinition> QueueDefinitions { get; } = [];

    public ObservableCollection<ScheduleEditorViewModel> Schedules { get; } = [];

    public ObservableCollection<BandwidthProfileEditorViewModel> BandwidthProfiles { get; } = [];

    public ObservableCollection<DownloadQueueDefinition> SelectedQueueDependencies { get; } = [];

    public ObservableCollection<DiagnosticEvent> DiagnosticEvents { get; } = [];

    public ObservableCollection<TransferDiagnosticEntryViewModel> SelectedTransferDiagnostics { get; } = [];

    public ObservableCollection<TransferHealthProbeStage> TransferHealthProbeStages { get; } = [];

    public bool CanRunTransferHealthProbe => HasSelectedDownload && !IsTransferHealthProbeRunning;

    public ObservableCollection<DownloadTimelineEntry> SelectedDownloadTimeline { get; } = [];

    public ObservableCollection<MediaFormatViewModel> MediaVideoFormats { get; } = [];

    public ObservableCollection<MediaFormatViewModel> MediaAudioFormats { get; } = [];

    public ObservableCollection<MediaFormatViewModel> MediaSubtitleFormats { get; } = [];

    public ObservableCollection<ConversionPreset> ConversionPresets { get; } = [];

    public ObservableCollection<ConversionJobViewModel> ConversionJobs { get; } = [];

    public IReadOnlyList<string> DuplicateBehaviors { get; }

    public IReadOnlyList<string> ChecksumAlgorithms { get; } =
        [DownloadChecksumService.Sha256, DownloadChecksumService.Sha512];

    public IReadOnlyList<DownloadPriority> DownloadPriorities { get; } = Enum.GetValues<DownloadPriority>();

    public IReadOnlyList<DownloadBackendPreference> DownloadBackendPreferences { get; } =
        Enum.GetValues<DownloadBackendPreference>();

    public IReadOnlyList<TransferPolicyBehavior> TransferPolicyBehaviors { get; } = Enum.GetValues<TransferPolicyBehavior>();

    public IReadOnlyList<NetworkCostOverride> NetworkCostOverrides { get; } = Enum.GetValues<NetworkCostOverride>();

    public IReadOnlyList<PowerSourceOverride> PowerSourceOverrides { get; } = Enum.GetValues<PowerSourceOverride>();

    public ObservableCollection<string> DownloadStatusFilters { get; } = [];

    public string PlatformDescription { get; }

    public string RuntimeDescription { get; }

    [ObservableProperty]
    private LanguageDefinition? selectedLanguage;

    [ObservableProperty]
    private bool useSystemLanguage;

    [ObservableProperty]
    private bool highContrastEnabled;

    [ObservableProperty]
    private bool announceStatusChanges = true;

    [ObservableProperty]
    private string uiScalePercent = "100";

    [ObservableProperty]
    private NavigationItem? selectedSection;

    [ObservableProperty]
    private DownloadItemViewModel? selectedDownload;

    [ObservableProperty]
    private int bulkSelectionCount;

    public bool HasBulkSelection => BulkSelectionCount > 0;

    public string BulkSelectionSummary
        => string.Format(
            _localization.Culture,
            _localization.Get("ui_bulk_selection_summary", "{0} selected"),
            BulkSelectionCount);

    [ObservableProperty]
    private string downloadSearchText = string.Empty;

    [ObservableProperty]
    private string selectedDownloadStatus = string.Empty;

    [ObservableProperty]
    private DownloadCategoryDefinition? selectedCategory;

    [ObservableProperty]
    private DownloadQueueDefinition? selectedQueue;

    [ObservableProperty]
    private DownloadQueueDefinition? selectedQueueDependencyCandidate;

    [ObservableProperty]
    private DownloadQueueDefinition? selectedExistingQueueDependency;

    [ObservableProperty]
    private bool selectedQueueRequiresSuccessfulDependencies = true;

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
    private string mirrorUrls = string.Empty;

    [ObservableProperty]
    private string expectedChecksumAlgorithm = DownloadChecksumService.Sha256;

    [ObservableProperty]
    private string expectedChecksum = string.Empty;

    [ObservableProperty]
    private bool previousSessionWasUnclean;

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
    private DownloadPriority newDownloadPriority = DownloadPriority.Normal;

    [ObservableProperty]
    private DownloadBackendPreference newDownloadBackendPreference = DownloadBackendPreference.Automatic;

    [ObservableProperty]
    private bool newDownloadAllowBackendFallback = true;

    [ObservableProperty]
    private DownloadPriority selectedDownloadPriority = DownloadPriority.Normal;

    [ObservableProperty]
    private string speedLimitKbps = string.Empty;

    [ObservableProperty]
    private string operationMessage = "Ready";

    [ObservableProperty]
    private bool isOperationMessageVisible = true;

    [ObservableProperty]
    private bool isDownloadsLoading = true;

    public bool HasDownloads => Downloads.Count > 0;

    public bool HasFilteredDownloads => FilteredDownloads.Count > 0;

    public bool ShowDownloadsEmptyState => !IsDownloadsLoading && !HasDownloads;

    public bool ShowDownloadFilterEmptyState => !IsDownloadsLoading && HasDownloads && !HasFilteredDownloads;

    public bool HasSelectedDownload => SelectedDownload is not null;

    public bool HasNoSelectedDownload => SelectedDownload is null;

    public int RecoveryItemCount => RecoveryCandidates.Count;

    public bool HasRecoveryItems => RecoveryItemCount > 0;

    public bool ShowRecoveryReview => PreviousSessionWasUnclean || HasRecoveryItems;

    public string RecoveryReviewSummary
        => RecoveryItemCount switch
        {
            0 => _localization["ui_recovery_unclean_no_damage"],
            1 => _localization["ui_recovery_items_one"],
            _ => string.Format(
                _localization.Culture,
                _localization["ui_recovery_items_many"],
                RecoveryItemCount)
        };

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
    private ScheduleEditorViewModel? selectedSchedule;

    [ObservableProperty]
    private string newScheduleName = string.Empty;

    [ObservableProperty]
    private string schedulerRuntimeStatus = "Scheduler is not running.";

    [ObservableProperty]
    private string pendingCompletionAction = "No completion action is pending.";

    [ObservableProperty]
    private bool hasPendingCompletionAction;

    [ObservableProperty]
    private string completionCapabilitySummary = string.Empty;

    [ObservableProperty]
    private bool antivirusEnabled;

    [ObservableProperty]
    private string antivirusExecutablePath = string.Empty;

    [ObservableProperty]
    private string antivirusArguments = string.Empty;

    [ObservableProperty]
    private string antivirusTimeoutSeconds = "120";

    [ObservableProperty]
    private string queueRuntimeStatus = "No active queues";

    [ObservableProperty]
    private string selectedQueueBlockedReason = string.Empty;

    [ObservableProperty]
    private bool smartTransfersEnabled = true;

    [ObservableProperty]
    private BandwidthProfileEditorViewModel? selectedBandwidthProfile;

    [ObservableProperty]
    private BandwidthProfileEditorViewModel? activeBandwidthProfile;

    [ObservableProperty]
    private TransferPolicyBehavior selectedMeteredBehavior = TransferPolicyBehavior.UseProfile;

    [ObservableProperty]
    private BandwidthProfileEditorViewModel? meteredBandwidthProfile;

    [ObservableProperty]
    private TransferPolicyBehavior selectedBatteryBehavior = TransferPolicyBehavior.UseProfile;

    [ObservableProperty]
    private BandwidthProfileEditorViewModel? batteryBandwidthProfile;

    [ObservableProperty]
    private NetworkCostOverride selectedNetworkCostOverride = NetworkCostOverride.Auto;

    [ObservableProperty]
    private PowerSourceOverride selectedPowerSourceOverride = PowerSourceOverride.Auto;

    [ObservableProperty]
    private bool pauseTransfersWhenOffline = true;

    [ObservableProperty]
    private string transferPolicyStatus = "Smart transfer policy is starting.";

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
    private string browserExtensionSummary = "No browser extension has reported health yet.";

    [ObservableProperty]
    private string browserCompatibilityStatus = "Compatibility has not been negotiated.";

    [ObservableProperty]
    private string browserPermissionSummary = "Extension permissions have not been reported.";

    [ObservableProperty]
    private string browserCapabilitiesSummary = "Capabilities have not been reported.";

    [ObservableProperty]
    private string browserLastHealth = "No extension heartbeat received.";

    [ObservableProperty]
    private bool browserExtensionHealthy;

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
    private string conversionSourcePath = string.Empty;

    [ObservableProperty]
    private string conversionDestinationPath = string.Empty;

    [ObservableProperty]
    private ConversionPreset? selectedConversionPreset;

    [ObservableProperty]
    private ConversionJobViewModel? selectedConversionJob;

    [ObservableProperty]
    private bool conversionOverwriteExisting;

    [ObservableProperty]
    private string conversionHealth = "Conversion tool health has not been checked.";

    [ObservableProperty]
    private string conversionStatus = "No conversion job is active.";

    [ObservableProperty]
    private bool mediaPostConversionEnabled;

    [ObservableProperty]
    private ConversionPreset? mediaPostConversionPreset;

    [ObservableProperty]
    private string recoveryStatus = "Checking recovery state";

    [ObservableProperty]
    private string diagnosticBundlePath = "No diagnostic bundle exported yet";

    [ObservableProperty]
    private string selectedTransferDiagnosticSummary = "Select a download to inspect its instrumented transfer timeline.";

    [ObservableProperty]
    private string transferHealthProbeStatus = "Select a download, then run the bounded live probe.";

    [ObservableProperty]
    private bool isTransferHealthProbeRunning;

    public IEnumerable<DownloadQueueDefinition> QueueDependencyCandidates
    {
        get
        {
            HashSet<string> excluded = new(SelectedQueue?.DependsOnQueueIds ?? [], StringComparer.Ordinal)
            {
                SelectedQueue?.Id ?? string.Empty
            };
            return QueueDefinitions.Where(queue =>
                !excluded.Contains(queue.Id)
                && (SelectedQueue is null
                    || !DownloadQueueDependencyGraph
                        .GetStartOrder(queue.Id, QueueDefinitions)
                        .Contains(SelectedQueue.Id, StringComparer.Ordinal)));
        }
    }

    public bool IsSelectedQueueActive
        => SelectedQueue is not null && _downloadManager.QueueRuntime.IsActive(SelectedQueue.Id);

    public bool IsDownloadsVisible
        => string.Equals(SelectedSection?.Id, "downloads", StringComparison.Ordinal);

    public bool IsRecoveryVisible
        => string.Equals(SelectedSection?.Id, "recovery", StringComparison.Ordinal);

    public bool IsQueuesVisible
        => string.Equals(SelectedSection?.Id, "queues", StringComparison.Ordinal);

    public bool IsSchedulerVisible
        => string.Equals(SelectedSection?.Id, "scheduler", StringComparison.Ordinal);

    public bool IsBrowserIntegrationVisible
        => string.Equals(SelectedSection?.Id, "browser", StringComparison.Ordinal);

    public bool IsMediaVisible
        => string.Equals(SelectedSection?.Id, "media", StringComparison.Ordinal);

    public bool IsConversionVisible
        => string.Equals(SelectedSection?.Id, "conversion", StringComparison.Ordinal);

    public bool IsSettingsVisible
        => string.Equals(SelectedSection?.Id, "settings", StringComparison.Ordinal);

    public bool IsDiagnosticsVisible
        => string.Equals(SelectedSection?.Id, "diagnostics", StringComparison.Ordinal);

    public bool IsDashboardSummaryVisible
        => IsDownloadsVisible || IsQueuesVisible;

    public bool IsPlaceholderVisible
        => !IsDownloadsVisible
            && !IsRecoveryVisible
            && !IsQueuesVisible
            && !IsSchedulerVisible
            && !IsBrowserIntegrationVisible
            && !IsMediaVisible
            && !IsConversionVisible
            && !IsSettingsVisible
            && !IsDiagnosticsVisible;

    partial void OnSelectedSectionChanged(NavigationItem? value)
    {
        CurrentTitle = value?.Title ?? _localization["nav_downloads"];
        CurrentSummary = value?.Summary ?? string.Empty;
        OnPropertyChanged(nameof(IsDownloadsVisible));
        OnPropertyChanged(nameof(IsRecoveryVisible));
        OnPropertyChanged(nameof(IsQueuesVisible));
        OnPropertyChanged(nameof(IsSchedulerVisible));
        OnPropertyChanged(nameof(IsBrowserIntegrationVisible));
        OnPropertyChanged(nameof(IsMediaVisible));
        OnPropertyChanged(nameof(IsConversionVisible));
        OnPropertyChanged(nameof(IsSettingsVisible));
        OnPropertyChanged(nameof(IsDiagnosticsVisible));
        OnPropertyChanged(nameof(IsDashboardSummaryVisible));
        OnPropertyChanged(nameof(IsPlaceholderVisible));
    }


    partial void OnDownloadSearchTextChanged(string value)
        => RefreshFilteredDownloads();

    partial void OnSelectedDownloadStatusChanged(string value)
        => RefreshFilteredDownloads();

    partial void OnSelectedDownloadChanged(DownloadItemViewModel? value)
    {
        RefreshSelectedDownloadTimeline(value?.Id);
        ApplySelectedTransferDiagnostics();
        TransferHealthProbeStages.Clear();
        TransferHealthProbeStatus = value is null
            ? "Select a download, then run the bounded live probe."
            : $"Ready to probe {value.Source.GetLeftPart(UriPartial.Authority)} and the destination disk.";
        SelectedDownloadPriority = value?.Priority ?? DownloadPriority.Normal;
        ApplySelectedHistoryItem(value);
        SelectedDownloadTags = value?.TagsText ?? string.Empty;
        RefreshSelectedDownloadNotificationState();
        RelinkDestinationPath = value?.DestinationPath ?? string.Empty;
        OnPropertyChanged(nameof(HasSelectedDownload));
        OnPropertyChanged(nameof(HasNoSelectedDownload));
        OnPropertyChanged(nameof(CanRunTransferHealthProbe));
    }

    partial void OnIsTransferHealthProbeRunningChanged(bool value)
        => OnPropertyChanged(nameof(CanRunTransferHealthProbe));

    partial void OnOperationMessageChanged(string value)
        => IsOperationMessageVisible = !string.IsNullOrWhiteSpace(value);

    partial void OnIsDownloadsLoadingChanged(bool value)
    {
        OnPropertyChanged(nameof(ShowDownloadsEmptyState));
        OnPropertyChanged(nameof(ShowDownloadFilterEmptyState));
    }

    partial void OnBulkSelectionCountChanged(int value)
    {
        OnPropertyChanged(nameof(HasBulkSelection));
        OnPropertyChanged(nameof(BulkSelectionSummary));
    }

    partial void OnSelectedMediaVideoFormatChanged(MediaFormatViewModel? value)
    {
        if (value?.Format.StreamKind == MediaStreamKind.Muxed)
        {
            SelectedMediaAudioFormat = null;
        }

        RefreshMediaSelectionSummary();
    }

    partial void OnConversionSourcePathChanged(string value)
    {
        if (!string.IsNullOrWhiteSpace(value) && SelectedConversionPreset is not null)
        {
            UpdateSuggestedConversionDestination(value, SelectedConversionPreset);
        }
    }

    partial void OnSelectedConversionPresetChanged(ConversionPreset? value)
    {
        if (value is not null && !string.IsNullOrWhiteSpace(ConversionSourcePath))
        {
            UpdateSuggestedConversionDestination(ConversionSourcePath, value);
        }
    }

    partial void OnSelectedQueueChanged(DownloadQueueDefinition? value)
    {
        SelectedQueueRequiresSuccessfulDependencies = value?.RequireSuccessfulDependencies ?? true;
        RefreshSelectedQueueDependencies();
        OnPropertyChanged(nameof(IsSelectedQueueActive));
        OnPropertyChanged(nameof(QueueDependencyCandidates));
        ApplyQueueRuntime(_downloadManager.QueueRuntime);
    }

    partial void OnSelectedQueueRequiresSuccessfulDependenciesChanged(bool value)
    {
        if (SelectedQueue is null || SelectedQueue.RequireSuccessfulDependencies == value)
        {
            return;
        }

        ReplaceSelectedQueue(SelectedQueue with { RequireSuccessfulDependencies = value });
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
        int focusedExisting = 0;
        HashSet<string> existingIds = Downloads.Select(static item => item.Id).ToHashSet(StringComparer.Ordinal);
        List<string> failures = [];

        foreach (Uri source in sources)
        {
            try
            {
                string? fileName = sources.Count == 1 && !string.IsNullOrWhiteSpace(CustomFileName)
                    ? CustomFileName.Trim()
                    : null;
                (string? savedUsername, string? savedPassword) = ResolveServerCredential(source);
                DownloadRequest request = new(
                    source,
                    DestinationFolder,
                    fileName,
                    headers,
                    EmptyToNull(Username) ?? savedUsername,
                    EmptyToNull(Password) ?? savedPassword,
                    EmptyToNull(Cookie),
                    EmptyToNull(Referer),
                    EmptyToNull(UserAgent),
                    SelectedQueue?.Id,
                    ResolveCategoryId(source, SelectedCategory?.Id),
                    speedLimit,
                    duplicateBehavior,
                    ConnectionCount: ParseInteger(DefaultConnectionCount, 4),
                    Priority: NewDownloadPriority,
                    SourcePage: ParseOptionalHttpUri(Referer),
                    Mirrors: DownloadInputParser.ParseUrls(MirrorUrls),
                    ExpectedChecksumAlgorithm: EmptyToNull(ExpectedChecksumAlgorithm),
                    ExpectedChecksum: EmptyToNull(ExpectedChecksum),
                    BackendPreference: NewDownloadBackendPreference,
                    AllowBackendFallback: NewDownloadAllowBackendFallback,
                    Tags: DownloadMetadata.ParseTags(NewDownloadTags));

                string downloadId = await _downloadManager.AddAsync(request);
                DownloadItemViewModel? existingDownload = Downloads.FirstOrDefault(item =>
                    string.Equals(item.Id, downloadId, StringComparison.Ordinal));
                if (existingIds.Add(downloadId))
                {
                    added++;
                }
                else
                {
                    SelectedDownload = existingDownload;
                    focusedExisting++;
                }
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
            MirrorUrls = string.Empty;
            ExpectedChecksum = string.Empty;
            NewDownloadTags = string.Empty;
            CustomFileName = string.Empty;
            if (!RememberLastRequestMetadata)
            {
                RequestHeaders = string.Empty;
                Username = string.Empty;
                Password = string.Empty;
                Cookie = string.Empty;
                Referer = string.Empty;
                UserAgent = string.Empty;
            }
        }

        string focusMessage = focusedExisting > 0
            ? $" Focused {focusedExisting} existing duplicate URL{(focusedExisting == 1 ? string.Empty : "s")}."
            : string.Empty;
        OperationMessage = failures.Count == 0
            ? $"Added {added} download{(added == 1 ? string.Empty : "s")}.{focusMessage}"
            : $"Added {added}; {failures.Count} failed: {failures[0]}{focusMessage}";
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
    private async Task VerifySelectedDownloadAsync()
    {
        if (SelectedDownload is null)
        {
            return;
        }

        try
        {
            DownloadVerificationResult result = await _downloadManager.VerifyAsync(SelectedDownload.Id);
            OperationMessage = result.Message;
        }
        catch (IOException exception)
        {
            OperationMessage = exception.Message;
        }
        catch (UnauthorizedAccessException exception)
        {
            OperationMessage = exception.Message;
        }
        catch (InvalidOperationException exception)
        {
            OperationMessage = exception.Message;
        }
    }

    [RelayCommand]
    private async Task RepairSelectedDownloadAsync()
    {
        if (SelectedDownload is null)
        {
            return;
        }

        try
        {
            DownloadRepairResult result = await _downloadManager.RepairAsync(SelectedDownload.Id);
            OperationMessage = result.Message;
        }
        catch (IOException exception)
        {
            OperationMessage = exception.Message;
        }
        catch (UnauthorizedAccessException exception)
        {
            OperationMessage = exception.Message;
        }
        catch (InvalidOperationException exception)
        {
            OperationMessage = exception.Message;
        }
    }

    [RelayCommand]
    private void ReviewRecoveryItems()
    {
        SelectedSection = Sections.FirstOrDefault(static section =>
                string.Equals(section.Id, "recovery", StringComparison.Ordinal))
            ?? SelectedSection;
        SelectedRecoveryCandidate ??= RecoveryCandidates.FirstOrDefault();
        PreviousSessionWasUnclean = false;
        OnPropertyChanged(nameof(ShowRecoveryReview));
        OperationMessage = RecoveryReviewSummary;
    }

    public async Task ImportMetalinkAsync(string path)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(path);
        if (string.IsNullOrWhiteSpace(DestinationFolder))
        {
            OperationMessage = "Choose a destination folder before importing a Metalink file.";
            return;
        }

        try
        {
            await using FileStream stream = new(
                path,
                FileMode.Open,
                FileAccess.Read,
                FileShare.Read,
                16 * 1024,
                FileOptions.Asynchronous | FileOptions.SequentialScan);
            IReadOnlyList<string> ids = await _downloadManager.AddMetalinkAsync(
                stream,
                DestinationFolder,
                SelectedQueue?.Id);
            OperationMessage = $"Added {ids.Count} download{(ids.Count == 1 ? string.Empty : "s")} from Metalink.";
        }
        catch (InvalidDataException exception)
        {
            OperationMessage = exception.Message;
        }
        catch (IOException exception)
        {
            OperationMessage = exception.Message;
        }
        catch (ArgumentException exception)
        {
            OperationMessage = exception.Message;
        }
        catch (UnauthorizedAccessException exception)
        {
            OperationMessage = exception.Message;
        }
    }

    [RelayCommand]
    private async Task RemoveSelectedAsync()
    {
        if (SelectedDownload is null)
        {
            return;
        }

        string id = SelectedDownload.Id;
        await _downloadManager.RemoveAsync(id);
        CanUndoHistoryRemoval = _downloadManager.UndoableRemovalCount > 0;
        OperationMessage = "Download removed from history. Use Undo to restore it.";
    }


    [RelayCommand]
    private void DismissOperationMessage()
        => IsOperationMessageVisible = false;

    [RelayCommand]
    private void ClearDownloadFilters()
    {
        DownloadSearchText = string.Empty;
        SelectedDownloadStatus = DownloadStatusFilters.Count > 0
            ? DownloadStatusFilters[0]
            : string.Empty;
        OperationMessage = "Download filters cleared.";
    }

    [RelayCommand]
    private void SelectAllVisible()
    {
        foreach (DownloadItemViewModel download in FilteredDownloads)
        {
            download.IsSelected = true;
        }

        RefreshBulkSelectionState();
        OperationMessage = $"Selected {FilteredDownloads.Count} visible download(s).";
    }

    [RelayCommand]
    private void ClearBulkSelection()
    {
        foreach (DownloadItemViewModel download in Downloads)
        {
            download.IsSelected = false;
        }

        RefreshBulkSelectionState();
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

        CanUndoHistoryRemoval = _downloadManager.UndoableRemovalCount > 0;
        OperationMessage = $"Removed {targets.Length} download(s) from history. Use Undo to restore them one at a time.";
    }

    [RelayCommand]
    private async Task SaveSettingsAsync()
    {
        if (!TryValidateSettingsEditor(out string validationMessage))
        {
            SettingsValidationMessage = validationMessage;
            OperationMessage = validationMessage;
            return;
        }

        SettingsValidationMessage = string.Empty;
        ApplicationSettings current = _settingsService.Current;
        int concurrency = int.TryParse(
            MaxConcurrentDownloads,
            System.Globalization.NumberStyles.Integer,
            System.Globalization.CultureInfo.InvariantCulture,
            out int parsedConcurrency)
                ? parsedConcurrency
                : current.MaxConcurrentDownloads;
        long defaultSpeed = ParseKilobytesPerSecond(DefaultSpeedLimitKbps) ?? 0;
        QueueScheduleDefinition[] schedules = Schedules
            .Select(static schedule => schedule.ToDefinition())
            .ToArray();
        if (schedules.Length == 0)
        {
            string fallbackQueueId = QueueDefinitions.Count > 0 ? QueueDefinitions[0].Id : "default";
            schedules =
            [
                new QueueScheduleDefinition(
                    "default-schedule",
                    "Default schedule",
                    false,
                    fallbackQueueId,
                    new TimeOnly(0, 0),
                    new TimeOnly(23, 59),
                    WeekDays.EveryDay,
                    MissedRunPolicy.Skip,
                    ScheduleCompletionAction.None)
            ];
        }

        QueueScheduleDefinition primarySchedule = schedules[0];
        int antivirusTimeout = int.TryParse(
            AntivirusTimeoutSeconds,
            System.Globalization.NumberStyles.Integer,
            System.Globalization.CultureInfo.InvariantCulture,
            out int parsedAntivirusTimeout)
                ? parsedAntivirusTimeout
                : 120;
        string[] antivirusArguments = AntivirusArguments
            .Split(LineSeparators, StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
        ApplicationSettings editorSettings = BuildSettingsFromEditor();
        ApplicationSettings updated = current with
        {
            DefaultDownloadDirectory = DestinationFolder,
            MaxConcurrentDownloads = concurrency,
            DefaultSpeedLimitBytesPerSecond = defaultSpeed,
            ClipboardMonitoringEnabled = ClipboardMonitoringEnabled,
            AutoAddClipboardLinks = AutoAddClipboardLinks,
            Categories = CategoryDefinitions.ToArray(),
            Queues = QueueDefinitions.ToArray(),
            Scheduler = new DownloadSchedulerSettings(
                primarySchedule.Enabled,
                primarySchedule.QueueId,
                primarySchedule.StartTime,
                primarySchedule.EndTime,
                primarySchedule.Days),
            Schedules = schedules,
            Antivirus = new AntivirusScanSettings(
                AntivirusEnabled,
                EmptyToNull(AntivirusExecutablePath),
                antivirusArguments,
                antivirusTimeout),
            Network = editorSettings.Network,
            DownloadBehavior = editorSettings.DownloadBehavior,
            Credentials = ServerCredentials.ToArray(),
            History = BuildHistoryRetentionSettings(),
            Localization = new LocalizationSettings(
                SelectedLanguage?.Id ?? "en",
                UseSystemLanguage),
            Accessibility = new AccessibilitySettings(
                HighContrastEnabled,
                ParseUiScalePercent(UiScalePercent),
                AnnounceStatusChanges),
            Aria2 = BuildAria2Settings(),
            Updates = new UpdateSettings(SelectedUpdateChannel, AutomaticUpdateChecks, NotifyWhenUpdateStaged),
            SmartTransfers = BuildSmartTransferSettings(),
            Organization = BuildOrganizationSettings()
        };

        await _settingsService.UpdateAsync(updated);
        OperationMessage = _localization["operation_settings_saved"];
    }

    [RelayCommand]
    private void AddSchedule()
    {
        if (QueueDefinitions.Count == 0)
        {
            OperationMessage = "Create a queue before adding a schedule.";
            return;
        }

        string name = string.IsNullOrWhiteSpace(NewScheduleName)
            ? $"Schedule {Schedules.Count + 1}"
            : NewScheduleName.Trim();
        string id = CreateStableId(name, Schedules.Select(static schedule => schedule.Id));
        QueueScheduleDefinition definition = new(
            id,
            name,
            false,
            SelectedQueue?.Id ?? QueueDefinitions[0].Id,
            new TimeOnly(0, 0),
            new TimeOnly(23, 59),
            WeekDays.EveryDay,
            MissedRunPolicy.Skip,
            ScheduleCompletionAction.None);
        ScheduleEditorViewModel editor = new(definition, QueueDefinitions, GetProfileDefinitions());
        Schedules.Add(editor);
        SelectedSchedule = editor;
        NewScheduleName = string.Empty;
        OperationMessage = "Schedule added; save settings to activate it.";
    }

    [RelayCommand]
    private void RemoveSelectedSchedule()
    {
        if (SelectedSchedule is null)
        {
            return;
        }

        int index = Schedules.IndexOf(SelectedSchedule);
        Schedules.Remove(SelectedSchedule);
        SelectedSchedule = Schedules.Count == 0
            ? null
            : Schedules[Math.Clamp(index, 0, Schedules.Count - 1)];
        OperationMessage = "Schedule removed; save settings to persist the change.";
    }

    [RelayCommand]
    private void CancelPendingCompletionAction()
    {
        OperationMessage = _queueSchedulerRuntime.CancelPendingAction()
            ? "Completion action cancellation requested."
            : "No completion action is pending.";
    }

    [RelayCommand]
    private void RefreshCompletionCapabilities()
    {
        CompletionCapabilitySummary = FormatCompletionCapabilities(_completionActionService.GetCapabilities());
        OperationMessage = "Platform completion capabilities refreshed.";
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
    private void AddQueueDependency()
    {
        if (SelectedQueue is null || SelectedQueueDependencyCandidate is null)
        {
            OperationMessage = "Select a queue and a dependency first.";
            return;
        }

        string[] dependencies = (SelectedQueue.DependsOnQueueIds ?? [])
            .Append(SelectedQueueDependencyCandidate.Id)
            .Distinct(StringComparer.Ordinal)
            .ToArray();
        ReplaceSelectedQueue(SelectedQueue with { DependsOnQueueIds = dependencies });
        SelectedQueueDependencyCandidate = null;
        OperationMessage = "Queue dependency added; save settings to persist it.";
    }

    [RelayCommand]
    private void RemoveQueueDependency()
    {
        if (SelectedQueue is null || SelectedExistingQueueDependency is null)
        {
            return;
        }

        string[] dependencies = (SelectedQueue.DependsOnQueueIds ?? [])
            .Where(id => !string.Equals(id, SelectedExistingQueueDependency.Id, StringComparison.Ordinal))
            .ToArray();
        ReplaceSelectedQueue(SelectedQueue with { DependsOnQueueIds = dependencies });
        SelectedExistingQueueDependency = null;
        OperationMessage = "Queue dependency removed; save settings to persist it.";
    }

    [RelayCommand]
    private async Task RefreshTransferPolicyAsync()
    {
        await _transferPolicyRuntime.RefreshAsync();
        OperationMessage = "Smart transfer environment refreshed.";
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
        string? blockedReason = _downloadManager.QueueRuntime.GetBlockedReason(SelectedQueue.Id);
        OperationMessage = blockedReason is null
            ? $"Queue '{SelectedQueue.Name}' started."
            : $"Queue '{SelectedQueue.Name}' is waiting: {blockedReason}";
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
    private async Task ApplySelectedDownloadPriorityAsync()
    {
        if (SelectedDownload is null)
        {
            OperationMessage = "Select a download first.";
            return;
        }

        await _downloadManager.SetPriorityAsync(SelectedDownload.Id, SelectedDownloadPriority);
        OperationMessage = $"Priority changed to {SelectedDownloadPriority}.";
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
    private Task RefreshBrowserDiagnosticsAsync()
    {
        BrowserHostInstallationStatus hostStatus = _browserHostInstaller.GetStatus();
        BrowserHostStatus = hostStatus.Message;
        ApplyBrowserStatus(_browserIntegrationService.Current);
        OperationMessage = "Browser integration diagnostics refreshed.";
        return Task.CompletedTask;
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
            MediaRequestMetadata metadata = BuildMediaMetadata();
            MediaCatalog catalog = await _mediaCatalogService.GetCatalogAsync(
                source,
                metadata,
                CancellationToken.None);
            if (catalog.Kind == MediaKind.Unknown || catalog.Formats.Count == 0)
            {
                MediaCatalogSummary = catalog.Description;
                return;
            }

            AddMediaInboxEntry(catalog, metadata, null, null, select: true);
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

        Uri? source = _currentMediaCatalog?.Source;
        if (source is null
            || !source.IsAbsoluteUri
            || source.Scheme is not ("http" or "https"))
        {
            MediaDownloadStatus = "Select a detected media item first.";
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
            _currentMediaMetadata,
            MaximumBytes: ParseMediaMaximumBytes());
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
            MediaDownloadStatus = $"Completed • {result.DownloadedFragments} fragment(s) • {LocaleFormatter.FormatBytes(result.DownloadedBytes, _localization.Culture)} • {result.DestinationPath}";
            if (MediaPostConversionEnabled && MediaPostConversionPreset is not null)
            {
                string conversionDestination = ConversionDestinationPlanner.CreatePostDownloadDestination(result.DestinationPath, MediaPostConversionPreset);
                string jobId = _conversionQueueService.Enqueue(new ConversionRequest(
                    result.DestinationPath,
                    conversionDestination,
                    MediaPostConversionPreset.Id));
                MediaDownloadStatus += $" • queued {MediaPostConversionPreset.Name} job {jobId[..8]}";
            }

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
        Task<FfmpegCapabilities> ffmpegTask = _ffmpegService.GetCapabilitiesAsync();
        Task<ExternalToolHealth> ytDlpTask = _ytDlpProvider.GetHealthAsync();
        await Task.WhenAll(ffmpegTask, ytDlpTask);
        FfmpegCapabilities ffmpeg = await ffmpegTask;
        ExternalToolHealth ytDlp = await ytDlpTask;
        string ffmpegVersion = ffmpeg.Health.IsAvailable
            ? ffmpeg.Health.Version ?? "available"
            : ffmpeg.Health.Message;
        MediaToolHealth = $"FFmpeg: {ffmpegVersion} • {ffmpeg.Summary} • yt-dlp: {(ytDlp.IsAvailable ? ytDlp.Version ?? "available" : ytDlp.Message)}";
    }

    public void SetConversionSourcePath(string sourcePath)
    {
        ConversionSourcePath = sourcePath;
        if (SelectedConversionPreset is not null)
        {
            UpdateSuggestedConversionDestination(sourcePath, SelectedConversionPreset);
        }
    }

    [RelayCommand]
    private void PrepareSelectedDownloadConversion()
    {
        if (SelectedDownload is null)
        {
            OperationMessage = "Select a completed download to convert.";
            return;
        }

        if (!string.Equals(SelectedDownload.StatusText, nameof(DownloadState.Completed), StringComparison.Ordinal)
            || !File.Exists(SelectedDownload.DestinationPath))
        {
            OperationMessage = "Only a completed download whose file still exists can be converted.";
            return;
        }

        SetConversionSourcePath(SelectedDownload.DestinationPath);
        SelectedSection = Sections.FirstOrDefault(static section => section.Title == "Conversion") ?? SelectedSection;
        ConversionStatus = $"Ready to convert {SelectedDownload.FileName}.";
    }

    [RelayCommand]
    private void EnqueueConversion()
    {
        if (SelectedConversionPreset is null)
        {
            ConversionStatus = "Choose a conversion preset.";
            return;
        }

        if (string.IsNullOrWhiteSpace(ConversionSourcePath)
            || string.IsNullOrWhiteSpace(ConversionDestinationPath))
        {
            ConversionStatus = "Choose a source file and destination path.";
            return;
        }

        try
        {
            string jobId = _conversionQueueService.Enqueue(new ConversionRequest(
                ConversionSourcePath.Trim(),
                ConversionDestinationPath.Trim(),
                SelectedConversionPreset.Id,
                ConversionOverwriteExisting));
            ConversionStatus = $"Queued {SelectedConversionPreset.Name} job {jobId[..8]}.";
        }
        catch (ArgumentException exception)
        {
            ConversionStatus = $"Unable to queue conversion: {exception.Message}";
        }
        catch (InvalidOperationException exception)
        {
            ConversionStatus = $"Unable to queue conversion: {exception.Message}";
        }
    }

    [RelayCommand]
    private void CancelSelectedConversion()
    {
        if (SelectedConversionJob is not null
            && _conversionQueueService.Cancel(SelectedConversionJob.Id))
        {
            ConversionStatus = "Conversion cancellation requested.";
        }
    }

    [RelayCommand]
    private void RemoveSelectedConversion()
    {
        if (SelectedConversionJob is not null
            && _conversionQueueService.Remove(SelectedConversionJob.Id))
        {
            ConversionStatus = "Conversion job removed from history.";
        }
    }

    [RelayCommand]
    private async Task RefreshConversionHealthAsync()
    {
        ConversionHealth = "Checking FFmpeg and FFprobe…";
        ExternalToolHealth health = await _conversionService.GetHealthAsync();
        ConversionHealth = health.IsAvailable
            ? $"{health.Version ?? "FFmpeg available"} • FFprobe available • safe no-shell invocation"
            : health.Message;
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

        if (AutoAddClipboardLinks)
        {
            NewDownloadUrls = string.Join(Environment.NewLine, urls.Select(static uri => uri.AbsoluteUri));
            OperationMessage = $"Captured {urls.Count} URL{(urls.Count == 1 ? string.Empty : "s")} from the clipboard.";
            await AddDownloadAsync();
            return;
        }

        SetClipboardReview(urls);
        OperationMessage = $"Clipboard link{(urls.Count == 1 ? string.Empty : "s")} ready for review.";
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
        _updateCancellation?.Cancel();
        _updateCancellation?.Dispose();
        _applicationState.Changed -= OnApplicationStateChanged;
        _settingsService.Changed -= OnSettingsChanged;
        _localization.Changed -= OnLocalizationChanged;
        _downloadManager.QueueRuntimeChanged -= OnQueueRuntimeChanged;
        _queueSchedulerRuntime.Changed -= OnSchedulerRuntimeChanged;
        _transferPolicyRuntime.Changed -= OnTransferPolicyChanged;
        _browserIntegrationService.StatusChanged -= OnBrowserStatusChanged;
        _browserIntegrationService.CaptureReceived -= OnBrowserCaptureReceived;
        _diagnosticEvents.Changed -= OnDiagnosticsChanged;
        _transferDiagnosticSource.Changed -= OnTransferDiagnosticsChanged;
        _transferHealthProbe.Changed -= OnTransferHealthProbeChanged;
        _subsystemHealthService.Changed -= OnSubsystemHealthChanged;
        _deterministicDownloadTestService.Changed -= OnDeterministicDownloadTestChanged;
        _notificationCenter.Changed -= OnNotificationCenterChanged;
        _downloadRecoveryCoordinator.Changed -= OnRecoveryCandidatesChanged;
        _conversionQueueService.Changed -= OnConversionQueueChanged;
        _aria2Service.Changed -= OnAria2SnapshotChanged;
        foreach (DownloadItemViewModel download in Downloads)
        {
            UnsubscribeDownloadItem(download);
        }

        GC.SuppressFinalize(this);
    }

    private void OnTransferDiagnosticsChanged(object? sender, EventArgs eventArgs)
    {
        if (_dispatcher.CheckAccess())
        {
            ApplySelectedTransferDiagnostics();
        }
        else
        {
            _dispatcher.Post(ApplySelectedTransferDiagnostics);
        }
    }

    private void OnTransferHealthProbeChanged(object? sender, EventArgs eventArgs)
    {
        if (_dispatcher.CheckAccess())
        {
            ApplyHealthProbeResult(_transferHealthProbe.LastResult);
        }
        else
        {
            _dispatcher.Post(() => ApplyHealthProbeResult(_transferHealthProbe.LastResult));
        }
    }

    private void ApplySelectedTransferDiagnostics()
    {
        SelectedTransferDiagnostics.Clear();
        if (SelectedDownload is null)
        {
            SelectedTransferDiagnosticSummary = "Select a download to inspect its instrumented transfer timeline.";
            ApplySelectedTransferInsights([]);
            return;
        }

        IReadOnlyList<TransferDiagnosticEvent> events = _transferDiagnosticSource.Snapshot(SelectedDownload.Id);
        foreach (TransferDiagnosticEvent item in events.Reverse().Take(250))
        {
            SelectedTransferDiagnostics.Add(new TransferDiagnosticEntryViewModel(item));
        }

        SelectedTransferDiagnosticSummary = events.Count == 0
            ? $"No structured transfer events have been recorded for {SelectedDownload.FileName} yet."
            : $"{events.Count} structured event{(events.Count == 1 ? string.Empty : "s")} for {SelectedDownload.FileName}; newest first.";
        ApplySelectedTransferInsights(events);
    }

    private void ApplyHealthProbeResult(TransferHealthProbeResult? result)
    {
        TransferHealthProbeStages.Clear();
        if (result is null)
        {
            return;
        }

        foreach (TransferHealthProbeStage stage in result.Stages)
        {
            TransferHealthProbeStages.Add(stage);
        }

        TransferHealthProbeStatus = $"{result.Summary} Target: {result.TargetOrigin}.";
    }

    [RelayCommand]
    private async Task RunTransferHealthProbeAsync()
    {
        if (SelectedDownload is null || IsTransferHealthProbeRunning)
        {
            return;
        }

        IsTransferHealthProbeRunning = true;
        TransferHealthProbeStatus = "Running bounded DNS, TCP, TLS, HTTP/range, and destination-disk checks…";
        try
        {
            string destinationDirectory = Path.GetDirectoryName(SelectedDownload.DestinationPath)
                ?? _settingsService.Current.DefaultDownloadDirectory;
            TransferHealthProbeResult result = await _transferHealthProbe
                .ProbeAsync(SelectedDownload.Source, destinationDirectory)
                .ConfigureAwait(false);
            await _dispatcher.InvokeAsync(() =>
            {
                ApplyHealthProbeResult(result);
                return Task.CompletedTask;
            });
        }
        catch (OperationCanceledException)
        {
            await _dispatcher.InvokeAsync(() =>
            {
                TransferHealthProbeStatus = "The live health probe was cancelled.";
                return Task.CompletedTask;
            });
        }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException or ArgumentException)
        {
            await _dispatcher.InvokeAsync(() =>
            {
                TransferHealthProbeStatus = $"Health probe failed: {exception.Message}";
                return Task.CompletedTask;
            });
        }
        finally
        {
            await _dispatcher.InvokeAsync(() =>
            {
                IsTransferHealthProbeRunning = false;
                return Task.CompletedTask;
            });
        }
    }

    [RelayCommand]
    private void ClearSelectedTransferDiagnostics()
    {
        if (SelectedDownload is null)
        {
            return;
        }

        _transferDiagnosticSource.Clear(SelectedDownload.Id);
        OperationMessage = "Selected transfer diagnostics cleared.";
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

    private void OnConversionQueueChanged(object? sender, ConversionQueueSnapshot snapshot)
    {
        if (_dispatcher.CheckAccess())
        {
            ApplyConversionQueue(snapshot);
        }
        else
        {
            _dispatcher.Post(() => ApplyConversionQueue(snapshot));
        }
    }

    private void ApplyConversionQueue(ConversionQueueSnapshot snapshot)
    {
        string? selectedId = SelectedConversionJob?.Id;
        Dictionary<string, ConversionJobViewModel> existing = ConversionJobs
            .ToDictionary(static job => job.Id, StringComparer.Ordinal);
        ConversionJobs.Clear();
        foreach (ConversionJobSnapshot job in snapshot.Jobs.Reverse())
        {
            if (!existing.TryGetValue(job.Id, out ConversionJobViewModel? viewModel))
            {
                viewModel = new ConversionJobViewModel(job);
            }
            else
            {
                viewModel.Apply(job);
            }

            ConversionJobs.Add(viewModel);
        }

        SelectedConversionJob = ConversionJobs.FirstOrDefault(
            job => string.Equals(job.Id, selectedId, StringComparison.Ordinal))
            ?? (ConversionJobs.Count > 0 ? ConversionJobs[0] : null);
        ConversionJobSnapshot? active = snapshot.ActiveJobId is null
            ? null
            : snapshot.Jobs.FirstOrDefault(
                job => string.Equals(job.Id, snapshot.ActiveJobId, StringComparison.Ordinal));
        ConversionStatus = active is null
            ? snapshot.Jobs.Any(static job => job.State == ConversionJobState.Queued)
                ? "Conversion jobs are waiting in the queue."
                : "No conversion job is active."
            : active.StatusMessage;
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

    private void OnSchedulerRuntimeChanged(object? sender, SchedulerRuntimeSnapshot snapshot)
    {
        if (_dispatcher.CheckAccess())
        {
            ApplySchedulerRuntime(snapshot);
        }
        else
        {
            _dispatcher.Post(() => ApplySchedulerRuntime(snapshot));
        }
    }

    private void OnTransferPolicyChanged(object? sender, TransferPolicySnapshot snapshot)
    {
        if (_dispatcher.CheckAccess())
        {
            ApplyTransferPolicy(snapshot);
        }
        else
        {
            _dispatcher.Post(() => ApplyTransferPolicy(snapshot));
        }
    }

    private void ApplyTransferPolicy(TransferPolicySnapshot snapshot)
        => TransferPolicyStatus = snapshot.StatusMessage;

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
                MediaRequestMetadata metadata = new(request.Headers, request.Cookie, request.Referer, request.UserAgent);
                MediaCatalog catalog = await _mediaCatalogService.GetCatalogAsync(
                    request.Url,
                    metadata)
                    .ConfigureAwait(false);
                if (catalog.Kind == MediaKind.Unknown || catalog.Formats.Count == 0)
                {
                    eventArgs.Reject(catalog.Description);
                    SetBrowserCaptureFailure(catalog.Description);
                    return;
                }

                eventArgs.Accept($"media-{Guid.NewGuid():N}");
                _dispatcher.Post(() =>
                {
                    AddMediaInboxEntry(
                        catalog,
                        metadata,
                        request.SourcePage,
                        request.Browser,
                        select: SelectedMediaInboxItem is null);
                    OperationMessage = $"Added media from {request.Browser ?? "browser"} to the detection inbox.";
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
            (string? savedUsername, string? savedPassword) = ResolveServerCredential(request.Url);
            NetworkSettings network = settings.Network ?? NetworkSettings.Default;
            DownloadRequest downloadRequest = new(
                request.Url,
                settings.DefaultDownloadDirectory,
                request.FileName,
                request.Headers,
                Username: savedUsername,
                Password: savedPassword,
                Cookie: request.Cookie,
                Referer: request.Referer,
                UserAgent: request.UserAgent,
                QueueId: request.QueueId,
                CategoryId: ResolveCategoryId(request.Url, request.CategoryId),
                ConnectionCount: request.Method == "GET" ? network.DefaultConnectionCount : 1,
                Method: request.Method,
                RequestBody: request.GetRequestBody(),
                RequestBodyContentType: request.RequestBodyContentType,
                SourcePage: ParseOptionalHttpUri(request.Referer));
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
        IsDownloadsLoading = !snapshot.CoreReady;
        CoreStatus = snapshot.CoreReady ? _localization["core_ready"] : _localization["core_starting"];
        ActiveDownloadCount = snapshot.ActiveDownloadCount;
        AggregateSpeed = LocaleFormatter.FormatRate(snapshot.AggregateBytesPerSecond, _localization.Culture);

        Dictionary<string, DownloadItemViewModel> existing = Downloads
            .ToDictionary(static item => item.Id, StringComparer.Ordinal);

        foreach (DownloadSnapshot download in snapshot.Downloads)
        {
            bool hadPreviousState = _lastDownloadStates.TryGetValue(download.Id, out DownloadState previousState);
            bool stateChanged = !hadPreviousState || previousState != download.State;
            _lastDownloadStates[download.Id] = download.State;

            if (existing.Remove(download.Id, out DownloadItemViewModel? item))
            {
                item.Apply(download, _localization);
            }
            else
            {
                DownloadItemViewModel added = new(download, _localization);
                SubscribeDownloadItem(added);
                Downloads.Add(added);
            }

            if (stateChanged)
            {
                AppendTimeline(download);
                if (hadPreviousState && download.State == DownloadState.Completed)
                {
                    _ = _notificationCenter.PublishAsync(
                        "Download completed",
                        download.FileName,
                        NotificationCenterSeverity.Success,
                        download.Id,
                        ShouldShowDesktopNotification(download.Id));
                }
                else if (hadPreviousState && download.State == DownloadState.Failed)
                {
                    _ = _notificationCenter.PublishAsync(
                        "Download failed",
                        download.FileName,
                        NotificationCenterSeverity.Error,
                        download.Id,
                        ShouldShowDesktopNotification(download.Id));
                }
            }
        }

        foreach (DownloadItemViewModel removed in existing.Values)
        {
            UnsubscribeDownloadItem(removed);
            Downloads.Remove(removed);
        }

        RefreshBulkSelectionState();
        RefreshFilteredDownloads();
        RefreshDestinationConflictPreview();
        OnPropertyChanged(nameof(AggregateProgressText));
        OnPropertyChanged(nameof(MiniDownloads));
        OnPropertyChanged(nameof(RecoveryItemCount));
        OnPropertyChanged(nameof(HasRecoveryItems));
        OnPropertyChanged(nameof(ShowRecoveryReview));
        OnPropertyChanged(nameof(RecoveryReviewSummary));
    }

    private void ApplyQueueRuntime(QueueRuntimeSnapshot snapshot)
    {
        string[] active = QueueDefinitions
            .Where(queue => snapshot.IsActive(queue.Id))
            .Select(queue => $"{queue.Name} ({snapshot.GetRunningCount(queue.Id)} running)")
            .ToArray();
        string[] blocked = QueueDefinitions
            .Select(queue => (Queue: queue, Reason: snapshot.GetBlockedReason(queue.Id)))
            .Where(static item => item.Reason is not null)
            .Select(static item => $"{item.Queue.Name}: {item.Reason}")
            .ToArray();
        QueueRuntimeStatus = active.Length == 0
            ? blocked.Length == 0 ? "No active queues" : string.Join(" • ", blocked)
            : blocked.Length == 0
                ? string.Join(" • ", active)
                : $"{string.Join(" • ", active)} • Waiting: {string.Join(" • ", blocked)}";
        SelectedQueueBlockedReason = SelectedQueue is null
            ? string.Empty
            : snapshot.GetBlockedReason(SelectedQueue.Id) ?? string.Empty;
        OnPropertyChanged(nameof(IsSelectedQueueActive));
    }

    private void ApplySchedulerRuntime(SchedulerRuntimeSnapshot snapshot)
    {
        SchedulerRuntimeStatus = snapshot.StatusMessage;
        PendingCompletionAction = snapshot.PendingAction is null
            ? "No completion action is pending."
            : $"{snapshot.PendingAction.ScheduleName}: {snapshot.PendingAction.Message}";
        HasPendingCompletionAction = snapshot.PendingAction is not null;
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

        BrowserExtensionHealthy = string.Equals(status.ExtensionCompatibility, "compatible", StringComparison.Ordinal)
            && status.LastExtensionHealthAt is DateTimeOffset heartbeat
            && DateTimeOffset.UtcNow - heartbeat < TimeSpan.FromMinutes(3);
        BrowserExtensionSummary = status.ExtensionBrowser is null
            ? "No browser extension has reported health yet. Open the extension popup to connect."
            : $"{status.ExtensionBrowser} • extension {status.ExtensionVersion ?? "unknown"} • Manifest V{status.ExtensionManifestVersion?.ToString(CultureInfo.InvariantCulture) ?? "?"}";
        BrowserCompatibilityStatus = status.ExtensionCompatibility switch
        {
            "compatible" => $"Compatible with native protocol {status.ProtocolVersion}.",
            "extension_outdated" => $"Extension is outdated; install version {BrowserNativeProtocol.MinimumExtensionVersion} or newer.",
            "host_outdated" => "The XDM native host is older than the extension.",
            "protocol_mismatch" => "Extension and native host protocol versions do not match.",
            _ => "Compatibility has not been negotiated."
        };
        BrowserPermissionSummary = status.ExtensionEnhancedAccessGranted switch
        {
            true => $"Enhanced metadata access granted • {status.ExtensionGrantedOrigins?.Count ?? 0} origin pattern(s) • private mode {(status.ExtensionIncognitoAllowed == true ? "allowed" : "blocked")}",
            false => $"Least-privilege URL-only mode • private mode {(status.ExtensionIncognitoAllowed == true ? "allowed" : "blocked")}",
            _ => "Extension permissions have not been reported."
        };
        BrowserCapabilitiesSummary = status.ExtensionCapabilities is { Count: > 0 }
            ? string.Join(" • ", status.ExtensionCapabilities.Where(static value => !value.StartsWith("host-version:", StringComparison.Ordinal)).Take(8))
            : "Capabilities have not been reported.";
        BrowserLastHealth = status.LastExtensionHealthAt is DateTimeOffset lastHealth
            ? $"Last extension heartbeat: {lastHealth.ToLocalTime():g}"
            : "No extension heartbeat received.";
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

    private void OnMediaProgress(MediaDownloadProgress progress)
    {
        MediaDownloadStatus = $"{progress.Stage} • {progress.Message} • {LocaleFormatter.FormatBytes(progress.DownloadedBytes, _localization.Culture)}";
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
        string? selectedScheduleId = SelectedSchedule?.Id;
        string? selectedProfileId = SelectedBandwidthProfile?.Id;
        SmartTransferSettings smartTransfers = (settings.SmartTransfers ?? SmartTransferSettings.Default).Normalize();
        BandwidthProfiles.Clear();
        foreach (BandwidthProfile profile in smartTransfers.Profiles)
        {
            BandwidthProfiles.Add(new BandwidthProfileEditorViewModel(profile));
        }

        SmartTransfersEnabled = smartTransfers.Enabled;
        SelectedMeteredBehavior = smartTransfers.MeteredBehavior;
        SelectedBatteryBehavior = smartTransfers.BatteryBehavior;
        SelectedNetworkCostOverride = smartTransfers.NetworkCostOverride;
        SelectedPowerSourceOverride = smartTransfers.PowerSourceOverride;
        PauseTransfersWhenOffline = smartTransfers.PauseWhenOffline;
        SelectedBandwidthProfile = BandwidthProfiles.FirstOrDefault(profile =>
            string.Equals(profile.Id, selectedProfileId, StringComparison.Ordinal))
            ?? BandwidthProfiles.FirstOrDefault();
        ActiveBandwidthProfile = BandwidthProfiles.FirstOrDefault(profile =>
            string.Equals(profile.Id, smartTransfers.ActiveProfileId, StringComparison.Ordinal));
        MeteredBandwidthProfile = BandwidthProfiles.FirstOrDefault(profile =>
            string.Equals(profile.Id, smartTransfers.MeteredProfileId, StringComparison.Ordinal));
        BatteryBandwidthProfile = BandwidthProfiles.FirstOrDefault(profile =>
            string.Equals(profile.Id, smartTransfers.BatteryProfileId, StringComparison.Ordinal));
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

        Schedules.Clear();
        foreach (QueueScheduleDefinition schedule in settings.Schedules ?? [])
        {
            Schedules.Add(new ScheduleEditorViewModel(schedule, QueueDefinitions, GetProfileDefinitions()));
        }

        SelectedSchedule = Schedules.FirstOrDefault(schedule =>
            string.Equals(schedule.Id, selectedScheduleId, StringComparison.Ordinal))
            ?? (Schedules.Count > 0 ? Schedules[0] : null);
        AntivirusScanSettings antivirus = settings.Antivirus ?? AntivirusScanSettings.Disabled;
        AntivirusEnabled = antivirus.Enabled;
        AntivirusExecutablePath = antivirus.ExecutablePath ?? string.Empty;
        AntivirusArguments = string.Join(Environment.NewLine, antivirus.Arguments ?? []);
        AntivirusTimeoutSeconds = antivirus.TimeoutSeconds
            .ToString(System.Globalization.CultureInfo.InvariantCulture);
        LocalizationSettings localization = (settings.Localization ?? LocalizationSettings.Default).Normalize();
        AccessibilitySettings accessibility = (settings.Accessibility ?? AccessibilitySettings.Default).Normalize();
        UseSystemLanguage = localization.UseSystemLanguage;
        SelectedLanguage = AvailableLanguages.FirstOrDefault(language =>
            string.Equals(language.Id, localization.LanguageId, StringComparison.OrdinalIgnoreCase))
            ?? _localization.CurrentLanguage;
        HighContrastEnabled = accessibility.HighContrastEnabled;
        AnnounceStatusChanges = accessibility.AnnounceStatusChanges;
        UiScalePercent = accessibility.UiScalePercent.ToString(System.Globalization.CultureInfo.InvariantCulture);
        ApplySettingsParity(settings);
        ApplyAria2Settings(settings);
        ApplyHistorySettings(settings);
        ApplyOrganizationSettings(settings);
        UpdateSettings updateSettings = (settings.Updates ?? UpdateSettings.Default).Normalize();
        SelectedUpdateChannel = updateSettings.Channel;
        AutomaticUpdateChecks = updateSettings.AutomaticChecks;
        NotifyWhenUpdateStaged = updateSettings.NotifyWhenStaged;
        ApplyQueueRuntime(_downloadManager.QueueRuntime);
        ApplySchedulerRuntime(_queueSchedulerRuntime.Current);
        ApplyTransferPolicy(_transferPolicyRuntime.Current);
    }

    private BandwidthProfile[] GetProfileDefinitions()
        => BandwidthProfiles.Select(static profile => profile.ToDefinition()).ToArray();

    private SmartTransferSettings BuildSmartTransferSettings()
    {
        BandwidthProfile[] profiles = GetProfileDefinitions();
        string fallback = profiles.FirstOrDefault()?.Id ?? SmartTransferSettings.Default.ActiveProfileId;
        return new SmartTransferSettings(
            SmartTransfersEnabled,
            ActiveBandwidthProfile?.Id ?? fallback,
            SelectedMeteredBehavior,
            MeteredBandwidthProfile?.Id ?? fallback,
            SelectedBatteryBehavior,
            BatteryBandwidthProfile?.Id ?? fallback,
            SelectedNetworkCostOverride,
            SelectedPowerSourceOverride,
            PauseTransfersWhenOffline,
            profiles).Normalize();
    }

    private void RefreshSelectedQueueDependencies()
    {
        SelectedQueueDependencies.Clear();
        if (SelectedQueue is null)
        {
            SelectedQueueBlockedReason = string.Empty;
            return;
        }

        foreach (string dependencyId in SelectedQueue.DependsOnQueueIds ?? [])
        {
            DownloadQueueDefinition? dependency = QueueDefinitions.FirstOrDefault(queue =>
                string.Equals(queue.Id, dependencyId, StringComparison.Ordinal));
            if (dependency is not null)
            {
                SelectedQueueDependencies.Add(dependency);
            }
        }
    }

    private void ReplaceSelectedQueue(DownloadQueueDefinition replacement)
    {
        DownloadQueueDefinition? current = SelectedQueue;
        if (current is null)
        {
            return;
        }

        int index = QueueDefinitions.IndexOf(current);
        if (index < 0)
        {
            return;
        }

        QueueDefinitions[index] = replacement;
        SelectedQueue = replacement;
        SchedulerQueue = string.Equals(SchedulerQueue?.Id, replacement.Id, StringComparison.Ordinal)
            ? replacement
            : SchedulerQueue;
        OnPropertyChanged(nameof(QueueDependencyCandidates));
    }

    private static string FormatCompletionCapabilities(IReadOnlyList<CompletionActionCapability> capabilities)
        => string.Join(
            " • ",
            capabilities
                .Where(static capability => capability.Kind != ScheduleCompletionActionKind.None)
                .Select(static capability => $"{capability.Kind}: {(capability.IsSupported ? "available" : "unavailable")}"));


    private void SubscribeDownloadItem(DownloadItemViewModel download)
        => download.PropertyChanged += OnDownloadItemPropertyChanged;

    private void UnsubscribeDownloadItem(DownloadItemViewModel download)
        => download.PropertyChanged -= OnDownloadItemPropertyChanged;

    private void OnDownloadItemPropertyChanged(object? sender, PropertyChangedEventArgs eventArgs)
    {
        if (string.Equals(eventArgs.PropertyName, nameof(DownloadItemViewModel.IsSelected), StringComparison.Ordinal))
        {
            RefreshBulkSelectionState();
        }
    }

    private void RefreshBulkSelectionState()
        => BulkSelectionCount = Downloads.Count(static download => download.IsSelected);

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
            ? download.DownloadedBytes.ToString("N0", _localization.Culture) + " bytes received."
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
            download.RefreshFilePresence();
            bool statusMatches = string.Equals(status, _localization["status_all"], StringComparison.OrdinalIgnoreCase)
                || string.Equals(download.StatusText, status, StringComparison.OrdinalIgnoreCase);
            bool archiveMatches = search.Contains("archived:", StringComparison.OrdinalIgnoreCase)
                || !download.IsArchived;
            bool searchMatches = download.MatchesSearch(search);
            if (statusMatches && archiveMatches && searchMatches)
            {
                FilteredDownloads.Add(download);
            }
        }

        if (SelectedDownload is not null && !FilteredDownloads.Contains(SelectedDownload))
        {
            SelectedDownload = FilteredDownloads.Count > 0 ? FilteredDownloads[0] : null;
        }
        else if (SelectedDownload is null && FilteredDownloads.Count > 0)
        {
            SelectedDownload = FilteredDownloads[0];
        }

        OnPropertyChanged(nameof(HasDownloads));
        OnPropertyChanged(nameof(HasFilteredDownloads));
        OnPropertyChanged(nameof(ShowDownloadsEmptyState));
        OnPropertyChanged(nameof(ShowDownloadFilterEmptyState));
        OnPropertyChanged(nameof(HasSelectedDownload));
        OnPropertyChanged(nameof(HasNoSelectedDownload));
    }

    private void UpdateSuggestedConversionDestination(string sourcePath, ConversionPreset preset)
    {
        try
        {
            ConversionDestinationPath = ConversionDestinationPlanner.CreatePostDownloadDestination(sourcePath, preset);
        }
        catch (ArgumentException)
        {
        }
        catch (NotSupportedException)
        {
        }
        catch (PathTooLongException)
        {
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

    public void SelectSection(string id)
    {
        NavigationItem? section = Sections.FirstOrDefault(item => string.Equals(item.Id, id, StringComparison.Ordinal));
        if (section is not null)
        {
            SelectedSection = section;
        }
    }

    private void OnLocalizationChanged(object? sender, EventArgs eventArgs)
    {
        if (_dispatcher.CheckAccess())
        {
            ApplyLocalization();
        }
        else
        {
            _dispatcher.Post(ApplyLocalization);
        }
    }

    private void ApplyLocalization()
    {
        foreach (NavigationItem section in Sections)
        {
            section.Refresh();
        }

        RefreshDownloadStatusFilters();
        OnPropertyChanged(nameof(BulkSelectionSummary));
        OnPropertyChanged(nameof(RecoveryReviewSummary));
        foreach (DownloadItemViewModel download in Downloads)
        {
            download.RefreshLocalization(_localization);
        }
        RefreshAria2Localization();
        ApplyRecoveryCandidates(_downloadRecoveryCoordinator.Current);

        CurrentTitle = SelectedSection?.Title ?? _localization["nav_downloads"];
        CurrentSummary = SelectedSection?.Summary ?? string.Empty;
        CoreStatus = _applicationState.Current.CoreReady ? _localization["core_ready"] : _localization["core_starting"];
        AggregateSpeed = LocaleFormatter.FormatRate(_applicationState.Current.AggregateBytesPerSecond, _localization.Culture);
        RefreshFilteredDownloads();
    }

    private void RefreshDownloadStatusFilters()
    {
        int selectedIndex = Math.Max(0, DownloadStatusFilters.IndexOf(SelectedDownloadStatus));
        DownloadStatusFilters.Clear();
        DownloadStatusFilters.Add(_localization["status_all"]);
        foreach (DownloadState state in Enum.GetValues<DownloadState>())
        {
            DownloadStatusFilters.Add(_localization.GetStatus(state));
        }

        SelectedDownloadStatus = DownloadStatusFilters[Math.Min(selectedIndex, DownloadStatusFilters.Count - 1)];
    }

    private static int ParseUiScalePercent(string value)
        => int.TryParse(
            value,
            System.Globalization.NumberStyles.Integer,
            System.Globalization.CultureInfo.InvariantCulture,
            out int parsed)
                ? Math.Clamp(parsed, 75, 175)
                : 100;

}
