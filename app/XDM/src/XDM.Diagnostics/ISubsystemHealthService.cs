namespace XDM.Diagnostics;

public interface ISubsystemHealthService
{
    SubsystemHealthSnapshot Current { get; }

    event EventHandler<SubsystemHealthSnapshot>? Changed;

    Task<SubsystemHealthSnapshot> RefreshAsync(
        string destinationDirectory,
        CancellationToken cancellationToken = default);

    Task<SubsystemHealthSnapshot> RepairAsync(
        string repairActionId,
        string destinationDirectory,
        CancellationToken cancellationToken = default);
}
