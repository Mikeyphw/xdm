namespace XDM.Core.Downloads;

public enum DownloadRecoveryClassification
{
    ReadyToResume,
    NeedsRemoteValidation,
    NeedsRepair,
    MissingPartialFile,
    RemoteFileChanged,
    AlreadyCompleteNotFinalized,
    OrphanedArtifact
}
