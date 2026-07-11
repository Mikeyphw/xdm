using XDM.Core.Downloads;

namespace XDM.Core.Tests;

public sealed class DownloadSnapshotTests
{
    [Fact]
    public void CalculatesProgressAndRemainingTime()
    {
        DownloadSnapshot snapshot = new(
            "download-1",
            "file.bin",
            new Uri("https://example.test/file.bin"),
            "/tmp/file.bin",
            512,
            1024,
            256,
            DownloadState.Downloading,
            DateTimeOffset.UtcNow);

        Assert.Equal(0.5, snapshot.ProgressFraction);
        Assert.Equal(TimeSpan.FromSeconds(2), snapshot.EstimatedRemaining);
    }
}
