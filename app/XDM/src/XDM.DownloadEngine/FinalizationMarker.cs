namespace XDM.DownloadEngine;

public sealed record FinalizationMarker(
    int Version,
    long ExpectedLength,
    string? ChecksumAlgorithm,
    string? Checksum,
    DateTimeOffset CreatedAt,
    bool LocalIntegrityRecordOnly = false)
{
    public const int CurrentVersion = 1;
}
