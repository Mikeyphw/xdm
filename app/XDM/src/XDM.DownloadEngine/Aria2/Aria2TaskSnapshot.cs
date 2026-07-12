namespace XDM.DownloadEngine.Aria2;

public sealed record Aria2TaskSnapshot(
    string Gid,
    Aria2TaskStatus Status,
    string DisplayName,
    string? DestinationPath,
    long CompletedBytes,
    long TotalBytes,
    long DownloadSpeedBytesPerSecond,
    long UploadSpeedBytesPerSecond,
    int Connections,
    string? ErrorCode,
    string? ErrorMessage)
{
    public double? ProgressFraction
        => TotalBytes > 0 ? Math.Clamp((double)CompletedBytes / TotalBytes, 0d, 1d) : null;

    public bool CanPause => Status == Aria2TaskStatus.Active;

    public bool CanResume => Status == Aria2TaskStatus.Paused;

    public bool CanRemove => Status is Aria2TaskStatus.Waiting
        or Aria2TaskStatus.Active
        or Aria2TaskStatus.Paused
        or Aria2TaskStatus.Complete
        or Aria2TaskStatus.Error
        or Aria2TaskStatus.Removed;
}
