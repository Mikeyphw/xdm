namespace XDM.DownloadEngine;

public interface IDownloadManager
{
    Task InitializeAsync(CancellationToken cancellationToken = default);

    Task<string> AddAsync(DownloadRequest request, CancellationToken cancellationToken = default);

    Task PauseAsync(string downloadId, CancellationToken cancellationToken = default);

    Task ResumeAsync(string downloadId, CancellationToken cancellationToken = default);

    Task CancelAsync(string downloadId, CancellationToken cancellationToken = default);

    Task RetryAsync(string downloadId, CancellationToken cancellationToken = default);

    Task RemoveAsync(string downloadId, bool deletePartialFile = false, CancellationToken cancellationToken = default);
}
