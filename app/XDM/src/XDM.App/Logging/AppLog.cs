using Microsoft.Extensions.Logging;

namespace XDM.App.Logging;

internal static partial class AppLog
{
    [LoggerMessage(
        EventId = 1000,
        Level = LogLevel.Information,
        Message = "XDM Avalonia main window initialized with modern core services.")]
    public static partial void MainWindowInitialized(ILogger logger);
}
