namespace XDM.Core.Downloads;

public enum DownloadState
{
    Queued,
    Connecting,
    Downloading,
    Paused,
    Finalizing,
    Completed,
    Failed,
    Cancelled
}
