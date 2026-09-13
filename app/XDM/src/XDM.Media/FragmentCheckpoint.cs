namespace XDM.Media;

internal sealed record FragmentCheckpoint(
    string Source,
    string FormatId,
    IReadOnlyList<string> CompletedIds,
    long DownloadedBytes,
    DateTimeOffset UpdatedAtUtc)
{
    public const int CurrentVersion = 2;

    public int Version { get; init; } = CurrentVersion;

    public string? PlanId { get; init; }

    public IReadOnlyList<FragmentCheckpointEntry> Entries { get; init; } = [];

    public double LiveElapsedSeconds { get; init; }
}

internal sealed record FragmentCheckpointEntry(
    string Id,
    string Identity,
    string RelativePath,
    long Length,
    long Order,
    bool HasInitialization = false,
    string? InitializationIdentity = null);
