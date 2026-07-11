using System.Diagnostics;
using XDM.Core.Downloads;
using XDM.Core.State;

namespace XDM.Core.Tests;

public sealed class LargeHistoryPerformanceTests
{
    private const int HistorySize = 10_000;

    [Fact]
    public void ReplaceDownloadsHandlesLargeHistoryWithinBudget()
    {
        DownloadSnapshot[] downloads = CreateDownloads();
        ApplicationState state = new();

        Stopwatch stopwatch = Stopwatch.StartNew();
        state.ReplaceDownloads(downloads);
        ApplicationSnapshot snapshot = state.Current;
        stopwatch.Stop();

        Assert.Equal(HistorySize, snapshot.Downloads.Count);
        Assert.True(
            stopwatch.Elapsed < TimeSpan.FromSeconds(10),
            $"Replacing {HistorySize} history entries took {stopwatch.Elapsed}.");
    }

    [Fact]
    public void AggregateMetricsRemainResponsiveForLargeHistory()
    {
        ApplicationSnapshot snapshot = new(
            DateTimeOffset.UtcNow,
            CoreReady: true,
            CreateDownloads());

        Stopwatch stopwatch = Stopwatch.StartNew();
        int active = snapshot.ActiveDownloadCount;
        double speed = snapshot.AggregateBytesPerSecond;
        stopwatch.Stop();

        Assert.Equal(HistorySize / 2, active);
        Assert.Equal(HistorySize * 1024d, speed);
        Assert.True(
            stopwatch.Elapsed < TimeSpan.FromSeconds(10),
            $"Aggregating {HistorySize} history entries took {stopwatch.Elapsed}.");
    }

    private static DownloadSnapshot[] CreateDownloads()
    {
        DateTimeOffset now = DateTimeOffset.UtcNow;
        DownloadSnapshot[] downloads = new DownloadSnapshot[HistorySize];

        for (int index = 0; index < downloads.Length; index++)
        {
            downloads[index] = new(
                index.ToString(System.Globalization.CultureInfo.InvariantCulture),
                $"file-{index}.bin",
                new Uri($"https://example.invalid/files/{index}"),
                Path.Combine(Path.GetTempPath(), $"file-{index}.bin"),
                DownloadedBytes: index,
                TotalBytes: HistorySize,
                BytesPerSecond: 1024d,
                State: index % 2 == 0 ? DownloadState.Downloading : DownloadState.Completed,
                UpdatedAt: now);
        }

        return downloads;
    }
}
