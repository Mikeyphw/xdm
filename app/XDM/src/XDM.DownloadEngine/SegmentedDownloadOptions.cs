namespace XDM.DownloadEngine;

public sealed record SegmentedDownloadOptions(
    int DefaultConnectionCount = 4,
    int MaximumConnectionCount = 16,
    long MinimumFileSizeBytes = 1024 * 1024)
{
    public SegmentedDownloadOptions Normalize()
    {
        int maximum = Math.Clamp(MaximumConnectionCount, 1, 32);
        return this with
        {
            DefaultConnectionCount = Math.Clamp(DefaultConnectionCount, 1, maximum),
            MaximumConnectionCount = maximum,
            MinimumFileSizeBytes = Math.Max(1, MinimumFileSizeBytes)
        };
    }
}
