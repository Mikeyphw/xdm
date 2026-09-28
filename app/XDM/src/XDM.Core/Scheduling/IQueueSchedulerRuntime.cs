namespace XDM.Core.Scheduling;

public interface IQueueSchedulerRuntime : IDisposable, IAsyncDisposable
{
    bool IsRunning { get; }

    DateTimeOffset? NextEvaluationAt { get; }

    SchedulerRuntimeSnapshot Current { get; }

    event EventHandler<SchedulerRuntimeSnapshot>? Changed;

    Task InitializeAsync(CancellationToken cancellationToken = default);

    Task EvaluateAsync(CancellationToken cancellationToken = default);

    bool CancelPendingAction();
}
