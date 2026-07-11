namespace XDM.DownloadEngine;

internal sealed record SegmentedDownloadContext(
    Uri Source,
    string DestinationPath,
    IReadOnlyDictionary<string, string>? Headers,
    string? Username,
    string? Password,
    string? Cookie,
    string? Referer,
    string? UserAgent,
    int ConnectionCount,
    long SpeedLimitBytesPerSecond);

internal sealed record SegmentedDownloadResult(
    long TotalBytes,
    string? EntityTag,
    DateTimeOffset? LastModified);
