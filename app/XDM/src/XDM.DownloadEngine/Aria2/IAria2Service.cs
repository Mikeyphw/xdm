namespace XDM.DownloadEngine.Aria2;

public interface IAria2Service
{
    Aria2ServiceSnapshot Current { get; }

    event EventHandler<Aria2ServiceSnapshot>? Changed;

    Task InitializeAsync(CancellationToken cancellationToken = default);

    Task ConfigureAsync(XDM.Core.Settings.Aria2IntegrationSettings settings, CancellationToken cancellationToken = default);

    Task StartManagedProcessAsync(CancellationToken cancellationToken = default);

    Task StopManagedProcessAsync(CancellationToken cancellationToken = default);

    Task RefreshAsync(CancellationToken cancellationToken = default);

    Task<string> AddAsync(Aria2AddRequest request, CancellationToken cancellationToken = default);

    Task PauseAsync(string gid, CancellationToken cancellationToken = default);

    Task ResumeAsync(string gid, CancellationToken cancellationToken = default);

    Task RemoveAsync(string gid, CancellationToken cancellationToken = default);
}
