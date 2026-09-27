using Microsoft.Extensions.DependencyInjection;
using XDM.Core.Diagnostics;
using XDM.Core.State;
using XDM.Diagnostics;
using XDM.DownloadEngine;
using XDM.Platform;

namespace XDM.App.Services;

internal static class ShutdownCoordinator
{
    public static async Task ExecuteAsync(
        ServiceProvider services,
        IRecoveryService recovery,
        IDiagnosticEventStore diagnostics,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(services);
        ArgumentNullException.ThrowIfNull(recovery);
        ArgumentNullException.ThrowIfNull(diagnostics);

        DesktopGoDownloadExecutionBroker downloads = services.GetRequiredService<DesktopGoDownloadExecutionBroker>();
        bool clean = false;
        DownloadShutdownReport? report = null;
        try
        {
            report = await downloads.PrepareForShutdownAsync(cancellationToken).ConfigureAwait(false);
            recovery.BeginShutdown(report.ActiveDownloadIds);
            recovery.RecordCheckpointFlush(
                report.CheckpointFlushSucceeded,
                report.CheckpointsAttempted,
                report.CheckpointsWritten,
                report.FailedDownloadIds);
            clean = report.CheckpointFlushSucceeded;
            if (!clean)
            {
                diagnostics.Record(
                    DiagnosticSeverity.Warning,
                    "XDM-SHUTDOWN-CHECKPOINTS",
                    "Shutdown did not fully drain and checkpoint active transfers; recovery will keep the session unclean.");
            }
        }
        finally
        {
            // Teardown belongs to the clean-shutdown transaction. If it throws, the marker
            // remains in place and the next launch correctly enters recovery.
            services.Dispose();
        }

        if (clean && report is not null)
        {
            recovery.MarkCleanShutdown();
            diagnostics.Record(
                DiagnosticSeverity.Information,
                "XDM-SHUTDOWN-001",
                "Application shutdown completed cleanly after transfer checkpoints and service teardown.",
                new Dictionary<string, string?>
                {
                    ["sessionId"] = recovery.SessionId,
                    ["activeDownloads"] = report.ActiveDownloadIds.Count.ToString(System.Globalization.CultureInfo.InvariantCulture),
                    ["checkpointsWritten"] = report.CheckpointsWritten.ToString(System.Globalization.CultureInfo.InvariantCulture)
                });
        }
    }
}
