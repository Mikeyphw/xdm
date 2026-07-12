namespace XDM.DownloadEngine;

public sealed record ResumeCheckpoint(
    int Version,
    string DownloadId,
    Uri Source,
    string DestinationPath,
    long DownloadedBytes,
    long? TotalBytes,
    string? EntityTag,
    DateTimeOffset? LastModified,
    int ConnectionCount,
    DateTimeOffset UpdatedAt,
    string? ExpectedChecksumAlgorithm = null,
    string? ExpectedChecksum = null,
    IReadOnlyList<Uri>? Mirrors = null,
    IReadOnlyDictionary<int, long>? SegmentLengths = null)
{
    public const int CurrentVersion = 1;
}
