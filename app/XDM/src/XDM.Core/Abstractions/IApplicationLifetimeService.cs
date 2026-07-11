namespace XDM.Core.Abstractions;

public interface IApplicationLifetimeService
{
    Task RequestShutdownAsync(CancellationToken cancellationToken = default);

    Task ActivateMainWindowAsync(CancellationToken cancellationToken = default);
}
