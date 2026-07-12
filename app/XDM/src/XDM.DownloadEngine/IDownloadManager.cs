using XDM.Core.Downloads;
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

    Task SetPriorityAsync(
        string downloadId,
        DownloadPriority priority,
        CancellationToken cancellationToken = default);

    Task RemoveAsync(string downloadId, bool deletePartialFile = false, CancellationToken cancellationToken = default);

    Task DeleteAsync(
        string downloadId,
        DownloadDeletionScope scope,
        CancellationToken cancellationToken = default);

    Task RelocateAsync(
        string downloadId,
        string destinationPath,
        bool overwrite = false,
        CancellationToken cancellationToken = default);

    Task<string> RedownloadAsync(
        string downloadId,
        DuplicateFileBehavior duplicateBehavior = DuplicateFileBehavior.AutoRename,
        CancellationToken cancellationToken = default);

    Task RefreshSourceAsync(
        string downloadId,
        Uri source,
        Uri? sourcePage = null,
        CancellationToken cancellationToken = default);

    Task<int> PruneHistoryAsync(CancellationToken cancellationToken = default);

    Task StartQueueAsync(string queueId, CancellationToken cancellationToken = default);

    Task StopQueueAsync(string queueId, CancellationToken cancellationToken = default);

    Task MoveToQueueAsync(
        string downloadId,
        string queueId,
        int? queueOrder = null,
        CancellationToken cancellationToken = default);
}
