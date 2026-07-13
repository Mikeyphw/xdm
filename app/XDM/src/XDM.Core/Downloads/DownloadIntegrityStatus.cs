namespace XDM.Core.Downloads;

public enum DownloadIntegrityStatus
{
    Unknown,
    Checkpointed,
    RecoveryRequired,
    Verifying,
    Verified,
    LocalRecord,
    Mismatch,
    Repairing
}
