namespace XDM.Core.Product;

public sealed record UpdateManifestDocument(
    int SchemaVersion,
    string Version,
    string? ReleaseNotesUrl,
    IReadOnlyList<UpdatePackageDescriptor>? Packages);

public sealed record UpdatePackageDescriptor(
    string RuntimeIdentifier,
    string Url,
    string Sha256,
    long SizeBytes,
    string FileName);
