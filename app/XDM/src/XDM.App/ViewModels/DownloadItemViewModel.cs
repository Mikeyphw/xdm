using CommunityToolkit.Mvvm.ComponentModel;
using XDM.Core.Downloads;

namespace XDM.App.ViewModels;

public sealed partial class DownloadItemViewModel : ObservableObject
{
    public DownloadItemViewModel(DownloadSnapshot snapshot)
    {
        Id = snapshot.Id;
        source = snapshot.Source;
        destinationPath = snapshot.DestinationPath;
        sourcePage = snapshot.SourcePage;
        Apply(snapshot);
    }

    public string Id { get; }

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

    public void Apply(DownloadSnapshot snapshot)
    {
        Source = snapshot.Source;
        SourcePage = snapshot.SourcePage;
        DestinationPath = snapshot.DestinationPath;
        OnPropertyChanged(nameof(HasSourcePage));
        FileName = snapshot.FileName;
        StatusText = snapshot.State.ToString();
        ProgressPercent = (snapshot.ProgressFraction ?? 0d) * 100d;
        ProgressText = snapshot.TotalBytes is > 0
            ? $"{FormatBytes(snapshot.DownloadedBytes)} of {FormatBytes(snapshot.TotalBytes.Value)}"
            : FormatBytes(snapshot.DownloadedBytes);
        SpeedText = snapshot.BytesPerSecond > 0
            ? $"{FormatBytes((long)snapshot.BytesPerSecond)}/s"
            : "—";
        RemainingText = snapshot.EstimatedRemaining is TimeSpan remaining
            ? FormatRemaining(remaining)
            : "—";
        ErrorMessage = snapshot.ErrorMessage;
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

    private static string FormatBytes(long bytes)
    {
        string[] units = ["B", "KB", "MB", "GB", "TB"];
        double value = Math.Max(0, bytes);
        int unit = 0;
        while (value >= 1024 && unit < units.Length - 1)
        {
            value /= 1024;
            unit++;
        }

        return $"{value:0.#} {units[unit]}";
    }

    private static string FormatRemaining(TimeSpan remaining)
    {
        if (remaining.TotalHours >= 1)
        {
            return $"{(int)remaining.TotalHours}h {remaining.Minutes}m";
        }

        if (remaining.TotalMinutes >= 1)
        {
            return $"{remaining.Minutes}m {remaining.Seconds}s";
        }

        return $"{Math.Max(0, remaining.Seconds)}s";
    }
}
