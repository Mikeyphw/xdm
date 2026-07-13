namespace XDM.Core.Policies;

public interface ITransferPolicyRuntime : IDisposable, IAsyncDisposable
{
    TransferPolicySnapshot Current { get; }

    event EventHandler<TransferPolicySnapshot>? Changed;

    Task InitializeAsync(CancellationToken cancellationToken = default);

    Task RefreshAsync(CancellationToken cancellationToken = default);

    void SetScheduleProfileOverrides(IEnumerable<string> profileIds);
}
