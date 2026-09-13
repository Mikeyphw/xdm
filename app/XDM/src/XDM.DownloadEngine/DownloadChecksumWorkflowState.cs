namespace XDM.DownloadEngine;

public sealed record DownloadChecksumWorkflowState(
    int Version,
    string DestinationPath,
    string? ExpectedSha256,
    string? ExpectedSha512,
    string? ActualSha256,
    string? ActualSha512,
    DateTimeOffset? LastVerifiedAt,
    bool? IsMatch,
    bool LocalIntegrityRecordOnly,
    long VerificationBytesProcessed = 0,
    long? VerificationTotalBytes = null,
    string? OwnerDownloadId = null)
{
    public const int CurrentVersion = 2;

    public static DownloadChecksumWorkflowState Empty(string destinationPath, string? ownerDownloadId = null)
        => new(CurrentVersion, destinationPath, null, null, null, null, null, null, false, 0, null, ownerDownloadId);
}
