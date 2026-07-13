namespace XDM.DownloadEngine;

public sealed record PartialFileRepairRequest(
    Uri Source,
    string LocalPath,
    long ExpectedLength,
    string? EntityTag,
    DateTimeOffset? LastModified,
    IReadOnlyDictionary<string, string>? Headers = null,
    string? Username = null,
    string? Password = null,
    string? Cookie = null,
    string? Referer = null,
    string? UserAgent = null,
    int BlockSizeBytes = 4 * 1024 * 1024,
    bool RequireRemoteValidator = false);

public sealed record RepairedByteRange(long Start, long End)
{
    public long Length => End - Start + 1;
}

public sealed record PartialFileRepairResult(
    long BytesScanned,
    long BytesDownloaded,
    long BytesRepaired,
    IReadOnlyList<RepairedByteRange> RepairedRanges,
    string? EntityTag,
    DateTimeOffset? LastModified,
    bool UsedSavedBlockHashes);

public interface IPartialFileRepairService
{
    Task CaptureVerifiedStateAsync(
        string localPath,
        long expectedLength,
        string? entityTag,
        DateTimeOffset? lastModified,
        CancellationToken cancellationToken = default);

    Task<PartialFileRepairResult> RepairAsync(
        PartialFileRepairRequest request,
        IProgress<(long Processed, long Total)>? progress = null,
        CancellationToken cancellationToken = default);
}
