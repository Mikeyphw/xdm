namespace XDM.Core.Diagnostics;

public sealed record TransferDiagnosticEvent(
    DateTimeOffset Timestamp,
    string DownloadId,
    TransferDiagnosticStage Stage,
    TransferDiagnosticSeverity Severity,
    string Code,
    string Message,
    IReadOnlyDictionary<string, string?> Context);
