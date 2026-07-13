namespace XDM.Core.Product;

public sealed record UpdateCheckResult(
    string CurrentVersion,
    string AvailableVersion,
    bool UpdateAvailable,
    Uri? ReleaseNotes,
    UpdatePackageDescriptor? Package,
    string Message,
    UpdateChannel Channel = UpdateChannel.Stable,
    bool IsMandatory = false,
    DateTimeOffset? PublishedAtUtc = null);

public sealed record StagedUpdateResult(
    string Version,
    string PackagePath,
    string Sha256,
    long SizeBytes,
    string? Sha512 = null,
    string? ReceiptPath = null,
    string? TransactionPath = null);
