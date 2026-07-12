using XDM.Core.Downloads;
using XDM.Core.Settings;

namespace XDM.Core.Persistence;

public static class HistoryRetentionPolicy
{
    public static IReadOnlyList<PersistedDownload> Apply(
        IReadOnlyList<PersistedDownload> downloads,
        HistoryRetentionSettings settings,
        DateTimeOffset now)
    {
        ArgumentNullException.ThrowIfNull(downloads);
        ArgumentNullException.ThrowIfNull(settings);

        HistoryRetentionSettings normalized = settings.Normalize();
        if (!normalized.Enabled)
        {
            return downloads;
        }

        DateTimeOffset cutoff = now - TimeSpan.FromDays(normalized.RetentionDays);
        List<PersistedDownload> retained = downloads
            .Where(item => !IsTerminal(item.State) || item.UpdatedAt >= cutoff)
            .OrderByDescending(static item => item.UpdatedAt)
            .ToList();

        if (retained.Count <= normalized.MaximumEntries)
        {
            return retained;
        }

        List<PersistedDownload> active = retained
            .Where(static item => !IsTerminal(item.State))
            .ToList();
        int terminalCapacity = Math.Max(0, normalized.MaximumEntries - active.Count);
        active.AddRange(retained
            .Where(static item => IsTerminal(item.State))
            .Take(terminalCapacity));
        return active
            .OrderByDescending(static item => item.UpdatedAt)
            .ToArray();
    }

    private static bool IsTerminal(DownloadState state)
        => state is DownloadState.Completed or DownloadState.Failed or DownloadState.Cancelled;
}
