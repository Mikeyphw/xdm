namespace XDM.DownloadEngine;

public sealed record FtpTransferContext(
    DateTimeOffset? ExpectedLastModified = null,
    long? ExpectedLength = null,
    int ConnectTimeoutSeconds = 30,
    int RequestTimeoutSeconds = 0)
{
    public TimeSpan ConnectTimeout => TimeSpan.FromSeconds(Math.Clamp(ConnectTimeoutSeconds, 1, 300));

    public TimeSpan? RequestTimeout => RequestTimeoutSeconds <= 0
        ? null
        : TimeSpan.FromSeconds(Math.Clamp(RequestTimeoutSeconds, 1, 86400));
}
