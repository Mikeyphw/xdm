namespace XDM.Core.Diagnostics;

public interface ITransferDiagnosticSink
{
    void Record(
        string downloadId,
        TransferDiagnosticStage stage,
        TransferDiagnosticSeverity severity,
        string code,
        string message,
        IReadOnlyDictionary<string, string?>? context = null);
}
