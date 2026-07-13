namespace XDM.Core.Downloads;

public sealed record DownloadSnapshot(
    string Id,
    string FileName,
    Uri Source,
    string DestinationPath,
    long DownloadedBytes,
    long? TotalBytes,
    double BytesPerSecond,
    DownloadState State,
    DateTimeOffset UpdatedAt,
    string? ErrorMessage = null,
    string QueueId = "default",
    string? CategoryId = null,
    int QueueOrder = 0,
    int ConnectionCount = 1,
    DownloadPriority Priority = DownloadPriority.Normal,
    Uri? SourcePage = null,
    string? ExpectedChecksumAlgorithm = null,
    string? ExpectedChecksum = null,
    string? ActualChecksum = null,
    DateTimeOffset? LastVerifiedAt = null,
    DownloadIntegrityStatus IntegrityStatus = DownloadIntegrityStatus.Unknown,
    bool RecoveryRequired = false,
    string? RecoveryMessage = null,
    IReadOnlyList<Uri>? Mirrors = null,
    DownloadBackendPreference BackendPreference = DownloadBackendPreference.Automatic,
    DownloadBackendKind Backend = DownloadBackendKind.Native,
    string? BackendTaskId = null,
    string? BackendDecisionReason = null,
    bool AllowBackendFallback = true,
    IReadOnlyList<string>? Tags = null,
    bool IsArchived = false,
    string? ContentHashSha256 = null,
    string? DuplicateOfDownloadId = null,
    string? DuplicateReason = null,
    string? ExpectedSha256 = null,
    string? ExpectedSha512 = null,
    string? ActualSha256 = null,
    string? ActualSha512 = null,
    long VerificationBytesProcessed = 0,
    long? VerificationTotalBytes = null)
{
    public double? ProgressFraction
        => TotalBytes is > 0
            ? Math.Clamp((double)DownloadedBytes / TotalBytes.Value, 0d, 1d)
            : null;

    public TimeSpan? EstimatedRemaining
        => TotalBytes is > 0 && BytesPerSecond > 0 && DownloadedBytes < TotalBytes
            ? TimeSpan.FromSeconds((TotalBytes.Value - DownloadedBytes) / BytesPerSecond)
            : null;
}
