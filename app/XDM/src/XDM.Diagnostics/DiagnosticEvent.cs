namespace XDM.Diagnostics;

public sealed record DiagnosticEvent(
    DateTimeOffset Timestamp,
    DiagnosticSeverity Severity,
    string Code,
    string Message,
    IReadOnlyDictionary<string, string?> Context);
