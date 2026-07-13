namespace XDM.Core.Downloads;

public sealed record DownloadRecoveryCandidate(
    string Id,
    string? DownloadId,
    string FileName,
    Uri? Source,
    string DestinationPath,
    string PartialPath,
    long PartialBytes,
    long? ExpectedTotalBytes,
    DateTimeOffset? LastCheckpointAt,
    string ResumeValidatorStatus,
    string? EntityTag,
    DateTimeOffset? LastModified,
    string? ExpectedChecksumAlgorithm,
    string? ExpectedChecksum,
    DownloadRecoveryClassification Classification,
    string RecommendedAction,
    string UnsafeReason,
    bool IsOrphaned = false)
{
    public bool CanResume
        => DownloadId is not null
            && Classification is (DownloadRecoveryClassification.ReadyToResume
                or DownloadRecoveryClassification.NeedsRemoteValidation);

    public bool CanValidate
        => DownloadId is not null
            && Source is not null
            && Classification is (DownloadRecoveryClassification.ReadyToResume
                or DownloadRecoveryClassification.NeedsRemoteValidation);

    public bool CanRepair
        => DownloadId is not null
            && Classification is not DownloadRecoveryClassification.OrphanedArtifact;

    public bool HasExpectedChecksum => !string.IsNullOrWhiteSpace(ExpectedChecksum);
}
