namespace XDM.Diagnostics;

public interface ITransferHealthProbe
{
    event EventHandler? Changed;

    TransferHealthProbeResult? LastResult { get; }

    Task<TransferHealthProbeResult> ProbeAsync(
        Uri target,
        string destinationDirectory,
        CancellationToken cancellationToken = default);
}
