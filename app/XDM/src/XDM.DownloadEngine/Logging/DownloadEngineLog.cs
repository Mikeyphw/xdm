using System.Net;
using Microsoft.Extensions.Logging;

namespace XDM.DownloadEngine.Logging;

internal static partial class DownloadEngineLog
{
    [LoggerMessage(EventId = 2000, Level = LogLevel.Information, Message = "Download {DownloadId} started from {Source} at byte {Offset}.")]
    public static partial void DownloadStarted(ILogger logger, string downloadId, Uri source, long offset);

    [LoggerMessage(EventId = 2001, Level = LogLevel.Information, Message = "Download {DownloadId} completed at {DestinationPath}.")]
    public static partial void DownloadCompleted(ILogger logger, string downloadId, string destinationPath);

    [LoggerMessage(EventId = 2002, Level = LogLevel.Warning, Message = "Download {DownloadId} failed: {Message}")]
    public static partial void DownloadFailed(ILogger logger, string downloadId, string message, Exception exception);

    [LoggerMessage(EventId = 2003, Level = LogLevel.Warning, Message = "History persistence failed: {Message}")]
    public static partial void HistoryPersistenceFailed(ILogger logger, string message, Exception exception);

    [LoggerMessage(EventId = 2004, Level = LogLevel.Warning, Message = "Download {DownloadId} retrying attempt {Attempt}/{MaximumAttempts} after {DelayMilliseconds} ms: {Message}")]
    public static partial void DownloadRetrying(
        ILogger logger,
        string downloadId,
        int attempt,
        int maximumAttempts,
        double delayMilliseconds,
        string message);

    [LoggerMessage(EventId = 2005, Level = LogLevel.Warning, Message = "Server ignored range request for download {DownloadId} at byte {Offset}; restarting safely from zero after HTTP {StatusCode}.")]
    public static partial void RangeIgnored(
        ILogger logger,
        string downloadId,
        long offset,
        HttpStatusCode statusCode);
}
