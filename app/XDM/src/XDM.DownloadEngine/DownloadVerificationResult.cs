namespace XDM.DownloadEngine;

public sealed record DownloadVerificationResult(
    string DownloadId,
    string FilePath,
    string Algorithm,
    string ActualChecksum,
    string? ExpectedChecksum,
    bool IsMatch,
    long Length,
    string Message);
