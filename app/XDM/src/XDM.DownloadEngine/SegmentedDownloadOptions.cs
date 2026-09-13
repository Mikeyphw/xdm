namespace XDM.DownloadEngine;

public sealed record SegmentedDownloadOptions(
    int DefaultConnectionCount = 4,
    int MaximumConnectionCount = 16,
    long MinimumFileSizeBytes = 1024 * 1024,
    int RequestTimeoutSeconds = 0)
{
    public SegmentedDownloadOptions Normalize()
    {
        int maximum = Math.Clamp(MaximumConnectionCount, 1, 32);
        return this with
        {
            DefaultConnectionCount = Math.Clamp(DefaultConnectionCount, 1, maximum),
            MaximumConnectionCount = maximum,
            MinimumFileSizeBytes = Math.Max(1, MinimumFileSizeBytes),
            RequestTimeoutSeconds = Math.Clamp(RequestTimeoutSeconds, 0, 86400)
        };
    }

    public TimeSpan? RequestTimeout => RequestTimeoutSeconds == 0
        ? null
        : TimeSpan.FromSeconds(RequestTimeoutSeconds);
}

