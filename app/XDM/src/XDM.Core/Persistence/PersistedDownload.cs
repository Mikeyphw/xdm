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
    int QueueOrder = 0);
