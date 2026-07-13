namespace XDM.Core.Product;

public enum UpdateTransactionState
{
    Staged,
    Applying,
    AppliedPendingHealth,
    Healthy,
    RollingBack,
    RolledBack,
    Failed
}

public sealed record UpdateVerificationReceipt(
    int SchemaVersion,
    string Version,
    UpdateChannel Channel,
    string RuntimeIdentifier,
    string PackageFileName,
    string Sha256,
    string Sha512,
    long SizeBytes,
    DateTimeOffset VerifiedAtUtc,
    string? SbomUrl,
    string? ProvenanceUrl);

public sealed record UpdateTransactionDocument(
    int SchemaVersion,
    string TransactionId,
    string CurrentVersion,
    string TargetVersion,
    UpdateChannel Channel,
    string RuntimeIdentifier,
    string PackagePath,
    string PackageSha256,
    long PackageSizeBytes,
    string InstallRoot,
    string BackupPath,
    string CandidatePath,
    UpdateTransactionState State,
    DateTimeOffset CreatedAtUtc,
    DateTimeOffset UpdatedAtUtc,
    string? FailureMessage = null,
    string? ExecutableRelativePath = null);
