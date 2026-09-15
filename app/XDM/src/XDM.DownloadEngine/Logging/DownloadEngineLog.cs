using System.Diagnostics.CodeAnalysis;
using System.Net;
using Microsoft.Extensions.Logging;
using XDM.Core.Diagnostics;

namespace XDM.DownloadEngine.Logging;

[SuppressMessage("Performance", "CA1873:Avoid potentially expensive work before checking logging level", Justification = "All public wrappers check ILogger.IsEnabled before redacting diagnostic payloads and calling generated LoggerMessage cores.")]
internal static partial class DownloadEngineLog
{
    public static void DownloadStarted(ILogger logger, string downloadId, Uri source, long offset)
    {
        if (!logger.IsEnabled(LogLevel.Information))
        {
            return;
        }

        DownloadStartedCore(logger, downloadId, DiagnosticRedactor.RedactOrigin(source), offset);
    }

    public static void DownloadCompleted(ILogger logger, string downloadId, string destinationPath)
    {
        if (!logger.IsEnabled(LogLevel.Information))
        {
            return;
        }

        DownloadCompletedCore(logger, downloadId, DiagnosticRedactor.RedactPath(destinationPath));
    }

    public static void DownloadFailed(ILogger logger, string downloadId, string message, Exception exception)
    {
        if (!logger.IsEnabled(LogLevel.Warning))
        {
            return;
        }

        DownloadFailedCore(logger, downloadId, DiagnosticRedactor.Redact(string.IsNullOrWhiteSpace(message) ? exception.Message : message));
    }

    public static void HistoryPersistenceFailed(ILogger logger, string message, Exception exception)
    {
        if (!logger.IsEnabled(LogLevel.Warning))
        {
            return;
        }

        HistoryPersistenceFailedCore(logger, DiagnosticRedactor.Redact(string.IsNullOrWhiteSpace(message) ? exception.Message : message));
    }

    public static void DownloadRetrying(
        ILogger logger,
        string downloadId,
        int attempt,
        int maximumAttempts,
        double delayMilliseconds,
        string message)
    {
        if (!logger.IsEnabled(LogLevel.Warning))
        {
            return;
        }

        DownloadRetryingCore(
            logger,
            downloadId,
            attempt,
            maximumAttempts,
            delayMilliseconds,
            DiagnosticRedactor.Redact(message));
    }

    [LoggerMessage(EventId = 2000, Level = LogLevel.Information, Message = "Download {DownloadId} started from {SourceOrigin} at byte {Offset}.", SkipEnabledCheck = true)]
    private static partial void DownloadStartedCore(ILogger logger, string downloadId, string sourceOrigin, long offset);

    [LoggerMessage(EventId = 2001, Level = LogLevel.Information, Message = "Download {DownloadId} completed at {DestinationPath}.", SkipEnabledCheck = true)]
    private static partial void DownloadCompletedCore(ILogger logger, string downloadId, string destinationPath);

    [LoggerMessage(EventId = 2002, Level = LogLevel.Warning, Message = "Download {DownloadId} failed: {Message}", SkipEnabledCheck = true)]
    private static partial void DownloadFailedCore(ILogger logger, string downloadId, string message);

    [LoggerMessage(EventId = 2003, Level = LogLevel.Warning, Message = "History persistence failed: {Message}", SkipEnabledCheck = true)]
    private static partial void HistoryPersistenceFailedCore(ILogger logger, string message);

    [LoggerMessage(EventId = 2004, Level = LogLevel.Warning, Message = "Download {DownloadId} retrying attempt {Attempt}/{MaximumAttempts} after {DelayMilliseconds} ms: {Message}", SkipEnabledCheck = true)]
    private static partial void DownloadRetryingCore(
        ILogger logger,
        string downloadId,
        int attempt,
        int maximumAttempts,
        double delayMilliseconds,
        string message);

    public static void RangeIgnored(
        ILogger logger,
        string downloadId,
        long offset,
        HttpStatusCode statusCode)
    {
        if (!logger.IsEnabled(LogLevel.Warning))
        {
            return;
        }

        RangeIgnoredCore(logger, downloadId, offset, statusCode);
    }

    [LoggerMessage(EventId = 2005, Level = LogLevel.Warning, Message = "Server ignored range request for download {DownloadId} at byte {Offset}; restarting safely from zero after HTTP {StatusCode}.", SkipEnabledCheck = true)]
    private static partial void RangeIgnoredCore(
        ILogger logger,
        string downloadId,
        long offset,
        HttpStatusCode statusCode);

    public static void TransferDiagnosticsSinkFailed(ILogger logger, string downloadId, Exception exception)
    {
        if (!logger.IsEnabled(LogLevel.Debug))
        {
            return;
        }

        TransferDiagnosticsSinkFailedCore(logger, downloadId, DiagnosticRedactor.Redact(exception.Message));
    }

    [LoggerMessage(EventId = 2006, Level = LogLevel.Debug, Message = "Transfer diagnostics sink failed for download {DownloadId}: {Message}.", SkipEnabledCheck = true)]
    private static partial void TransferDiagnosticsSinkFailedCore(
        ILogger logger,
        string downloadId,
        string message);
}
