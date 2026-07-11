using XDM.Core.Queues;

namespace XDM.DownloadEngine;

public interface IDownloadManager
{
    event EventHandler<QueueRuntimeSnapshot>? QueueRuntimeChanged;

    QueueRuntimeSnapshot QueueRuntime { get; }

    Task InitializeAsync(CancellationToken cancellationToken = default);

    Task<string> AddAsync(DownloadRequest request, CancellationToken cancellationToken = default);

    Task PauseAsync(string downloadId, CancellationToken cancellationToken = default);

    Task ResumeAsync(string downloadId, CancellationToken cancellationToken = default);

    Task CancelAsync(string downloadId, CancellationToken cancellationToken = default);

    Task RetryAsync(string downloadId, CancellationToken cancellationToken = default);

    Task RemoveAsync(string downloadId, bool deletePartialFile = false, CancellationToken cancellationToken = default);

    Task StartQueueAsync(string queueId, CancellationToken cancellationToken = default);

    Task StopQueueAsync(string queueId, CancellationToken cancellationToken = default);

    Task MoveToQueueAsync(
        string downloadId,
        string queueId,
        int? queueOrder = null,
        CancellationToken cancellationToken = default);
}
