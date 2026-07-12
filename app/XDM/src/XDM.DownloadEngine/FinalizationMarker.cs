namespace XDM.DownloadEngine;

public sealed record FinalizationMarker(
    int Version,
    long ExpectedLength,
    string? ChecksumAlgorithm,
    string? Checksum,
    DateTimeOffset CreatedAt)
{
    public const int CurrentVersion = 1;
}
