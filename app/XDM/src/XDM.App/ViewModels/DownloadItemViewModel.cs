using CommunityToolkit.Mvvm.ComponentModel;
using XDM.App.Services;
using XDM.Core.Downloads;
using XDM.Core.Localization;

namespace XDM.App.ViewModels;

public sealed partial class DownloadItemViewModel : ObservableObject
{
    private DownloadSnapshot _snapshot;

    public DownloadItemViewModel(DownloadSnapshot snapshot, LocalizationService localization)
    {
        Id = snapshot.Id;
        _snapshot = snapshot;
        source = snapshot.Source;
        destinationPath = snapshot.DestinationPath;
        sourcePage = snapshot.SourcePage;
        Apply(snapshot, localization);
    }

    public string Id { get; }

    public DownloadState State => _snapshot.State;

    [ObservableProperty]
    private Uri source;

    [ObservableProperty]
    private Uri? sourcePage;

    [ObservableProperty]
    private string destinationPath;

    [ObservableProperty]
    private string fileName = string.Empty;

    [ObservableProperty]
    private string statusText = string.Empty;

    [ObservableProperty]
    private string progressText = string.Empty;

    [ObservableProperty]
    private string speedText = string.Empty;

    [ObservableProperty]
    private string remainingText = string.Empty;

    [ObservableProperty]
    private double progressPercent;

    [ObservableProperty]
    private string? errorMessage;

    [ObservableProperty]
    private string integrityStatusText = string.Empty;

    [ObservableProperty]
    private string? recoveryMessage;

    [ObservableProperty]
    private string? expectedChecksum;

    [ObservableProperty]
    private string? actualChecksum;

    [ObservableProperty]
    private string? lastVerifiedText;

    [ObservableProperty]
    private int mirrorCount;

    [ObservableProperty]
    private string backendText = string.Empty;

    [ObservableProperty]
    private string backendPreferenceText = string.Empty;

    [ObservableProperty]
    private string? backendTaskId;

    [ObservableProperty]
    private string? backendDecisionReason;

    [ObservableProperty]
    private string queueId = "default";

    [ObservableProperty]
    private int queueOrder;

    [ObservableProperty]
    private DownloadPriority priority = DownloadPriority.Normal;

    [ObservableProperty]
    private bool canPause;

    [ObservableProperty]
    private bool canResume;

    [ObservableProperty]
    private bool canCancel;

    [ObservableProperty]
    private bool isSelected;

    public bool HasSourcePage => SourcePage is not null;

    public bool HasError => !string.IsNullOrWhiteSpace(ErrorMessage);

    public bool HasRecoveryMessage => !string.IsNullOrWhiteSpace(RecoveryMessage);

    public bool RecoveryRequired => _snapshot.RecoveryRequired;

    public bool CanVerify => _snapshot.State == DownloadState.Completed;

    public bool CanRepair => _snapshot.RecoveryRequired
        || _snapshot.IntegrityStatus == DownloadIntegrityStatus.Mismatch
        || _snapshot.State is DownloadState.Failed or DownloadState.Cancelled or DownloadState.Paused;

    public void Apply(DownloadSnapshot snapshot, LocalizationService localization)
    {
        ArgumentNullException.ThrowIfNull(localization);
        _snapshot = snapshot;
        Source = snapshot.Source;
        SourcePage = snapshot.SourcePage;
        DestinationPath = snapshot.DestinationPath;
        OnPropertyChanged(nameof(HasSourcePage));
        OnPropertyChanged(nameof(State));
        FileName = snapshot.FileName;
        StatusText = localization.GetStatus(snapshot.State);
        ProgressPercent = (snapshot.ProgressFraction ?? 0d) * 100d;
        ProgressText = snapshot.TotalBytes is > 0
            ? $"{LocaleFormatter.FormatBytes(snapshot.DownloadedBytes, localization.Culture)} {localization["unit_of"]} {LocaleFormatter.FormatBytes(snapshot.TotalBytes.Value, localization.Culture)}"
            : LocaleFormatter.FormatBytes(snapshot.DownloadedBytes, localization.Culture);
        SpeedText = snapshot.BytesPerSecond > 0
            ? LocaleFormatter.FormatRate(snapshot.BytesPerSecond, localization.Culture)
            : "—";
        RemainingText = snapshot.EstimatedRemaining is TimeSpan remaining
            ? LocaleFormatter.FormatDuration(remaining, localization.Culture)
            : "—";
        ErrorMessage = snapshot.ErrorMessage;
        OnPropertyChanged(nameof(HasError));
        IntegrityStatusText = localization.Get(
            $"integrity_{snapshot.IntegrityStatus.ToString().ToLowerInvariant()}",
            snapshot.IntegrityStatus.ToString());
        RecoveryMessage = snapshot.RecoveryMessage;
        ExpectedChecksum = snapshot.ExpectedChecksum is null
            ? null
            : $"{snapshot.ExpectedChecksumAlgorithm}: {snapshot.ExpectedChecksum}";
        ActualChecksum = snapshot.ActualChecksum;
        LastVerifiedText = snapshot.LastVerifiedAt?.ToLocalTime().ToString("g", localization.Culture);
        MirrorCount = Math.Max(0, (snapshot.Mirrors?.Count ?? 1) - 1);
        BackendText = snapshot.Backend == DownloadBackendKind.Aria2 ? "aria2" : "Native";
        BackendPreferenceText = snapshot.BackendPreference.ToString();
        BackendTaskId = snapshot.BackendTaskId;
        BackendDecisionReason = snapshot.BackendDecisionReason;
        OnPropertyChanged(nameof(HasRecoveryMessage));
        OnPropertyChanged(nameof(RecoveryRequired));
        OnPropertyChanged(nameof(CanVerify));
        OnPropertyChanged(nameof(CanRepair));
        QueueId = snapshot.QueueId;
        QueueOrder = snapshot.QueueOrder;
        Priority = snapshot.Priority;
        CanPause = snapshot.State is DownloadState.Connecting or DownloadState.Downloading;
        CanResume = snapshot.State is DownloadState.Paused or DownloadState.Failed or DownloadState.Cancelled;
        CanCancel = snapshot.State is DownloadState.Queued
            or DownloadState.Connecting
            or DownloadState.Downloading
            or DownloadState.Paused;
    }

    public void RefreshLocalization(LocalizationService localization)
        => Apply(_snapshot, localization);
}
