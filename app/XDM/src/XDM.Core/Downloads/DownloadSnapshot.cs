namespace XDM.Core.Downloads;

public sealed record DownloadSnapshot(
    string Id,
    string FileName,
    Uri Source,
    string DestinationPath,
    long DownloadedBytes,
    long? TotalBytes,
    double BytesPerSecond,
    DownloadState State,
    DateTimeOffset UpdatedAt)
{
    public double? ProgressFraction
        => TotalBytes is > 0
            ? Math.Clamp((double)DownloadedBytes / TotalBytes.Value, 0d, 1d)
            : null;

    public TimeSpan? EstimatedRemaining
        => TotalBytes is > 0 && BytesPerSecond > 0 && DownloadedBytes < TotalBytes
            ? TimeSpan.FromSeconds((TotalBytes.Value - DownloadedBytes) / BytesPerSecond)
            : null;
}
