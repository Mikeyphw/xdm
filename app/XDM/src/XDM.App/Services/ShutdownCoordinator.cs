using Microsoft.Extensions.DependencyInjection;
using XDM.Core.Diagnostics;
using XDM.Core.State;
using XDM.Diagnostics;
using XDM.DownloadEngine;

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

        IDownloadManager downloads = services.GetRequiredService<IDownloadManager>();
        bool clean = false;
        DownloadShutdownReport? report = null;
        try
        {
            downloads.FreezeAdmission();

            string[] activeIds = services.GetRequiredService<IApplicationState>()
                .Current.Downloads
                .Where(static download => download.State is XDM.Core.Downloads.DownloadState.Connecting
                    or XDM.Core.Downloads.DownloadState.Downloading
                    or XDM.Core.Downloads.DownloadState.Finalizing)
                .Select(static download => download.Id)
                .OrderBy(static id => id, StringComparer.Ordinal)
                .ToArray();
            recovery.BeginShutdown(activeIds);

            report = await downloads.PrepareForShutdownAsync(cancellationToken).ConfigureAwait(false);
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
