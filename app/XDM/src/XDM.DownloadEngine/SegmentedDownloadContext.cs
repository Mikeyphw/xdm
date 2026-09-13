namespace XDM.DownloadEngine;

internal sealed record SegmentedDownloadContext(
    string DownloadId,
    Uri Source,
    string DestinationPath,
    IReadOnlyDictionary<string, string>? Headers,
    string? Username,
    string? Password,
    string? Cookie,
    string? Referer,
    string? UserAgent,
    int ConnectionCount,
    long SpeedLimitBytesPerSecond,
    long? ExpectedLength = null,
    string? ExpectedEntityTag = null,
    DateTimeOffset? ExpectedLastModified = null);

internal sealed record SegmentedDownloadResult(
    long TotalBytes,
    string? EntityTag,
    DateTimeOffset? LastModified);
