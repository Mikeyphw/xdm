namespace XDM.Core.Persistence;

public interface IDownloadHistoryStore
{
    Task<IReadOnlyList<PersistedDownload>> LoadAsync(CancellationToken cancellationToken = default);

    Task SaveAsync(
        IReadOnlyCollection<PersistedDownload> downloads,
        CancellationToken cancellationToken = default);
}
