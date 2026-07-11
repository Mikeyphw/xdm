namespace XDM.Diagnostics;

public interface IDiagnosticEventStore
{
    event EventHandler? Changed;

    IReadOnlyList<DiagnosticEvent> Snapshot();

    void Record(
        DiagnosticSeverity severity,
        string code,
        string message,
        IReadOnlyDictionary<string, string?>? context = null);

    void Clear();
}
