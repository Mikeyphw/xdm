namespace XDM.DownloadEngine;

public sealed record FtpDownloadResult(
    long DownloadedBytes,
    long? TotalBytes,
    DateTimeOffset? LastModified,
    bool Resumed);
