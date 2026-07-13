namespace XDM.Core.Diagnostics;

public sealed class NullTransferDiagnosticSink : ITransferDiagnosticSink
{
    public static NullTransferDiagnosticSink Instance { get; } = new();

    private NullTransferDiagnosticSink()
    {
    }

    public void Record(
        string downloadId,
        TransferDiagnosticStage stage,
        TransferDiagnosticSeverity severity,
        string code,
        string message,
        IReadOnlyDictionary<string, string?>? context = null)
    {
    }
}
