using XDM.Core.Downloads;

namespace XDM.Core.Persistence;

public sealed record PersistedDownload(
    string Id,
    Uri Source,
    string DestinationPath,
    long DownloadedBytes,
    long? TotalBytes,
    DownloadState State,
    DateTimeOffset UpdatedAt,
    string? ErrorMessage = null,
    string QueueId = "default",
    string? CategoryId = null,
    int QueueOrder = 0,
    string? EntityTag = null,
    DateTimeOffset? LastModified = null,
    int ConnectionCount = 4,
    string Method = "GET",
    DownloadPriority Priority = DownloadPriority.Normal);
