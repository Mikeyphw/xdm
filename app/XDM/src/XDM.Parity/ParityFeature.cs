namespace XDM.Parity;

public sealed record ParityFeature(
    string Id,
    string Area,
    string Name,
    ParityPriority Priority,
    ParityStatus Status,
    IReadOnlyList<string> LegacySources,
    IReadOnlyList<string> ImplementationPaths,
    IReadOnlyList<string> AutomatedTests,
    string TargetOverlay,
    string Notes);
