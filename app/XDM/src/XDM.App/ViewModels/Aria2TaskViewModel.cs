using CommunityToolkit.Mvvm.ComponentModel;
using XDM.App.Services;
using XDM.Core.Localization;
using XDM.DownloadEngine.Aria2;

namespace XDM.App.ViewModels;

public sealed partial class Aria2TaskViewModel : ObservableObject
{
    private Aria2TaskSnapshot _snapshot;

    public Aria2TaskViewModel(Aria2TaskSnapshot snapshot, LocalizationService localization)
    {
        ArgumentNullException.ThrowIfNull(snapshot);
        ArgumentNullException.ThrowIfNull(localization);
        Gid = snapshot.Gid;
        _snapshot = snapshot;
        Apply(snapshot, localization);
    }

    public string Gid { get; }

    public Aria2TaskStatus Status => _snapshot.Status;

    [ObservableProperty]
    private string displayName = string.Empty;

    [ObservableProperty]
    private string destinationPath = string.Empty;

    [ObservableProperty]
    private string statusText = string.Empty;

    [ObservableProperty]
    private string progressText = string.Empty;

    [ObservableProperty]
    private string speedText = "—";

    [ObservableProperty]
    private string connectionsText = "0";

    [ObservableProperty]
    private double progressPercent;

    [ObservableProperty]
    private bool isIndeterminate;

    [ObservableProperty]
    private bool canPause;

    [ObservableProperty]
    private bool canResume;

    [ObservableProperty]
    private bool canRemove;

    [ObservableProperty]
    private string? errorMessage;

    public void Apply(Aria2TaskSnapshot snapshot, LocalizationService localization)
    {
        ArgumentNullException.ThrowIfNull(snapshot);
        ArgumentNullException.ThrowIfNull(localization);
        _snapshot = snapshot;
        OnPropertyChanged(nameof(Status));
        DisplayName = snapshot.DisplayName;
        DestinationPath = snapshot.DestinationPath ?? string.Empty;
        StatusText = localization.Get($"aria2_status_{snapshot.Status.ToString().ToLowerInvariant()}", snapshot.Status.ToString());
        ProgressPercent = (snapshot.ProgressFraction ?? 0d) * 100d;
        IsIndeterminate = snapshot.ProgressFraction is null
            && snapshot.Status is Aria2TaskStatus.Active or Aria2TaskStatus.Waiting;
        ProgressText = snapshot.TotalBytes > 0
            ? $"{LocaleFormatter.FormatBytes(snapshot.CompletedBytes, localization.Culture)} {localization["unit_of"]} {LocaleFormatter.FormatBytes(snapshot.TotalBytes, localization.Culture)}"
            : LocaleFormatter.FormatBytes(snapshot.CompletedBytes, localization.Culture);
        SpeedText = snapshot.DownloadSpeedBytesPerSecond > 0
            ? LocaleFormatter.FormatRate(snapshot.DownloadSpeedBytesPerSecond, localization.Culture)
            : "—";
        ConnectionsText = snapshot.Connections.ToString(localization.Culture);
        CanPause = snapshot.CanPause;
        CanResume = snapshot.CanResume;
        CanRemove = snapshot.CanRemove;
        ErrorMessage = snapshot.ErrorMessage;
    }

    public void RefreshLocalization(LocalizationService localization)
        => Apply(_snapshot, localization);
}
