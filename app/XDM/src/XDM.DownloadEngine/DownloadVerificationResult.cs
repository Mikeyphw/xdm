namespace XDM.DownloadEngine;

public sealed record DownloadVerificationResult(
    string DownloadId,
    string FilePath,
    string Algorithm,
    string ActualChecksum,
    string? ExpectedChecksum,
    bool IsMatch,
    long Length,
    string Message,
    string? ActualSha256 = null,
    string? ActualSha512 = null,
    string? ExpectedSha256 = null,
    string? ExpectedSha512 = null,
    bool LocalIntegrityRecordOnly = false);
