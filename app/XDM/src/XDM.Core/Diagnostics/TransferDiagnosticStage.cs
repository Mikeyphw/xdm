namespace XDM.Core.Diagnostics;

public enum TransferDiagnosticStage
{
    Scheduling,
    Backend,
    Proxy,
    Connection,
    Http,
    Resume,
    Retry,
    Disk,
    Verification,
    Finalization
}
