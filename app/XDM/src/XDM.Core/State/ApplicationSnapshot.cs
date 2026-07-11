using XDM.Core.Downloads;

namespace XDM.Core.State;

public sealed record ApplicationSnapshot(
    DateTimeOffset StartedAt,
    bool CoreReady,
    IReadOnlyList<DownloadSnapshot> Downloads)
{
    public int ActiveDownloadCount
        => Downloads.Count(static download =>
            download.State is DownloadState.Connecting
                or DownloadState.Downloading
                or DownloadState.Finalizing);

    public double AggregateBytesPerSecond
        => Downloads.Sum(static download => download.BytesPerSecond);
}
