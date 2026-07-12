using XDM.Core.Downloads;
using XDM.Core.Persistence;
using XDM.Core.Settings;

namespace XDM.Core.Tests;

public sealed class HistoryRetentionPolicyTests
{
    private static readonly Uri Source = new("https://example.test/file.bin");

    [Fact]
    public void DisabledPolicyPreservesAllEntries()
    {
        PersistedDownload[] downloads =
        [
            Create("old", DownloadState.Completed, DateTimeOffset.UtcNow.AddYears(-2)),
            Create("active", DownloadState.Downloading, DateTimeOffset.UtcNow.AddYears(-2))
        ];

        IReadOnlyList<PersistedDownload> retained = HistoryRetentionPolicy.Apply(
            downloads,
            HistoryRetentionSettings.Default,
            DateTimeOffset.UtcNow);

        Assert.Same(downloads, retained);
    }

    [Fact]
    public void EnabledPolicyExpiresTerminalEntriesButPreservesActiveWork()
    {
        DateTimeOffset now = DateTimeOffset.UtcNow;
        PersistedDownload[] downloads =
        [
            Create("expired", DownloadState.Completed, now.AddDays(-31)),
            Create("recent", DownloadState.Failed, now.AddDays(-2)),
            Create("active", DownloadState.Paused, now.AddDays(-365))
        ];

        IReadOnlyList<PersistedDownload> retained = HistoryRetentionPolicy.Apply(
            downloads,
            new HistoryRetentionSettings(true, 30, 100),
            now);

        Assert.DoesNotContain(retained, static item => item.Id == "expired");
        Assert.Contains(retained, static item => item.Id == "recent");
        Assert.Contains(retained, static item => item.Id == "active");
    }

    [Fact]
    public void MaximumEntryLimitDropsOldestTerminalEntriesOnly()
    {
        DateTimeOffset now = DateTimeOffset.UtcNow;
        List<PersistedDownload> downloads =
        [
            Create("active", DownloadState.Queued, now.AddYears(-1))
        ];
        for (int index = 0; index < 150; index++)
        {
            downloads.Add(Create($"done-{index}", DownloadState.Completed, now.AddMinutes(-index)));
        }

        IReadOnlyList<PersistedDownload> retained = HistoryRetentionPolicy.Apply(
            downloads,
            new HistoryRetentionSettings(true, 3650, 100),
            now);

        Assert.Equal(100, retained.Count);
        Assert.Contains(retained, static item => item.Id == "active");
        Assert.Contains(retained, static item => item.Id == "done-0");
        Assert.DoesNotContain(retained, static item => item.Id == "done-149");
    }

    private static PersistedDownload Create(string id, DownloadState state, DateTimeOffset updatedAt)
        => new(
            id,
            Source,
            Path.Combine(Path.GetTempPath(), $"{id}.bin"),
            0,
            null,
            state,
            updatedAt);
}
