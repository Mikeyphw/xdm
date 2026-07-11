namespace XDM.Parity;

public sealed record ParityManifest(
    int SchemaVersion,
    string Baseline,
    DateTimeOffset UpdatedAt,
    IReadOnlyList<ParityFeature> Features);
