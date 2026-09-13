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
    bool IsOrphaned = false,
    bool RemoteIdentityValidated = false,
    bool RepairSupported = false,
    bool OperationInProgress = false)
{
    public bool CanResume
        => DownloadId is not null
            && !OperationInProgress
            && RemoteIdentityValidated
            && Classification == DownloadRecoveryClassification.ReadyToResume;

    public bool CanValidate
        => DownloadId is not null
            && Source is not null
            && !OperationInProgress
            && Classification == DownloadRecoveryClassification.NeedsRemoteValidation
            && Source.Scheme is "http" or "https";

    public bool CanRepair
        => DownloadId is not null
            && !OperationInProgress
            && RepairSupported
            && Classification == DownloadRecoveryClassification.NeedsRepair;

    public bool CanRestart
        => DownloadId is not null
            && !OperationInProgress
            && Classification != DownloadRecoveryClassification.OrphanedArtifact;

    public bool HasExpectedChecksum => !string.IsNullOrWhiteSpace(ExpectedChecksum);
}
