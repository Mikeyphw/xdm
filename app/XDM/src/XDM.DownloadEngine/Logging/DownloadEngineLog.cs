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
}
