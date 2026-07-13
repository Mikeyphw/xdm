using XDM.Core.Diagnostics;

namespace XDM.Diagnostics;

public sealed record TransferDiagnosticInsights(
    IReadOnlyDictionary<string, string?> ResponseHeaders,
    IReadOnlyList<TransferDiagnosticEvent> RetryHistory,
    IReadOnlyList<TransferSegmentDiagnostic> Segments,
    string ResumeAvailabilitySummary)
{
    public static TransferDiagnosticInsights Empty { get; } = new(
        new Dictionary<string, string?>(StringComparer.OrdinalIgnoreCase),
        [],
        [],
        "Resume capability has not been evaluated for this transfer.");
}
