namespace XDM.Core.Scheduling;

public interface ISchedulerStateStore
{
    Task<SchedulerRuntimeState> LoadAsync(CancellationToken cancellationToken = default);

    Task SaveAsync(SchedulerRuntimeState state, CancellationToken cancellationToken = default);
}
