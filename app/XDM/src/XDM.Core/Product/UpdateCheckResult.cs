namespace XDM.Core.Product;

public sealed record UpdateCheckResult(
    string CurrentVersion,
    string AvailableVersion,
    bool UpdateAvailable,
    Uri? ReleaseNotes,
    UpdatePackageDescriptor? Package,
    string Message);

public sealed record StagedUpdateResult(
    string Version,
    string PackagePath,
    string Sha256,
    long SizeBytes);
