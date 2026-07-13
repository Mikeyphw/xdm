namespace XDM.Core.Diagnostics;

public interface ITransferDiagnosticSource
{
    event EventHandler? Changed;

    IReadOnlyList<TransferDiagnosticEvent> Snapshot(string? downloadId = null);

    void Clear(string? downloadId = null);
}
