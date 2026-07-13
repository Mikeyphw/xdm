namespace XDM.DownloadEngine;

public sealed record DownloadShutdownReport(
    IReadOnlyList<string> ActiveDownloadIds,
    int CheckpointsAttempted,
    int CheckpointsWritten,
    IReadOnlyList<string> FailedDownloadIds,
    DateTimeOffset StartedAt,
    DateTimeOffset CompletedAt)
{
    public bool CheckpointFlushSucceeded => FailedDownloadIds.Count == 0;
}
