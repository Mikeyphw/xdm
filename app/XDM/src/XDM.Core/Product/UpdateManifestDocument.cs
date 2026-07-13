namespace XDM.Core.Product;

public sealed record UpdateManifestDocument(
    int SchemaVersion,
    string Version,
    string? ReleaseNotesUrl,
    IReadOnlyList<UpdatePackageDescriptor>? Packages,
    string? Channel = null,
    DateTimeOffset? PublishedAtUtc = null,
    string? MinimumSupportedVersion = null);

public sealed record UpdatePackageDescriptor(
    string RuntimeIdentifier,
    string Url,
    string Sha256,
    long SizeBytes,
    string FileName,
    string? Sha512 = null,
    string? SbomUrl = null,
    string? ProvenanceUrl = null);
