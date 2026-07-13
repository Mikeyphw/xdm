namespace XDM.Diagnostics;

public sealed record ApplicationSessionState(
    int Version,
    string SessionId,
    int ProcessId,
    DateTimeOffset StartedAt,
    bool SafeMode,
    DateTimeOffset? ShutdownStartedAt,
    DateTimeOffset? CheckpointFlushCompletedAt,
    bool? CheckpointFlushSucceeded,
    int CheckpointsAttempted,
    int CheckpointsWritten,
    string[] ActiveDownloadIds,
    string[] FailedCheckpointDownloadIds,
    DateTimeOffset? CleanShutdownAt)
{
    public const int CurrentVersion = 1;

    public bool IsClean => CleanShutdownAt is not null;
}
