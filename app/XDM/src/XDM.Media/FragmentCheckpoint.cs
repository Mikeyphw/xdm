namespace XDM.Media;

internal sealed record FragmentCheckpoint(
    string Source,
    string FormatId,
    IReadOnlyList<string> CompletedIds,
    long DownloadedBytes,
    DateTimeOffset UpdatedAtUtc);
