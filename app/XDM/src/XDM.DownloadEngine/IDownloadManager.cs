using XDM.Core.Downloads;
using XDM.Core.Queues;

namespace XDM.DownloadEngine;

public interface IDownloadManager
{
    event EventHandler<QueueRuntimeSnapshot>? QueueRuntimeChanged;

    QueueRuntimeSnapshot QueueRuntime { get; }

    Task InitializeAsync(CancellationToken cancellationToken = default);

    Task<string> AddAsync(DownloadRequest request, CancellationToken cancellationToken = default);


    Task<DownloadVerificationResult> VerifyAsync(
        string downloadId,
        CancellationToken cancellationToken = default);

    Task<DownloadRepairResult> RepairAsync(
        string downloadId,
        CancellationToken cancellationToken = default);

    Task<DownloadRepairResult> RestartFromZeroAsync(
        string downloadId,
        CancellationToken cancellationToken = default)
        => RepairAsync(downloadId, cancellationToken);

    Task<DownloadChecksumWorkflowState> GetChecksumWorkflowAsync(
        string downloadId,
        CancellationToken cancellationToken = default)
        => Task.FromException<DownloadChecksumWorkflowState>(
            new NotSupportedException("Checksum workflow details are unavailable."));

    Task SetExpectedChecksumsAsync(
        string downloadId,
        string? expectedSha256,
        string? expectedSha512,
        CancellationToken cancellationToken = default)
        => Task.FromException(new NotSupportedException("Checksum editing is unavailable."));

    Task<IReadOnlyList<string>> AddMetalinkAsync(
        Stream stream,
        string destinationDirectory,
        string? queueId = null,
        CancellationToken cancellationToken = default);

    Task PauseAsync(string downloadId, CancellationToken cancellationToken = default);

    Task ResumeAsync(string downloadId, CancellationToken cancellationToken = default);

    Task CancelAsync(string downloadId, CancellationToken cancellationToken = default);

    Task RetryAsync(string downloadId, CancellationToken cancellationToken = default);

    Task SetPriorityAsync(
        string downloadId,
        DownloadPriority priority,
        CancellationToken cancellationToken = default);

    Task RemoveAsync(string downloadId, bool deletePartialFile = false, CancellationToken cancellationToken = default);

    int UndoableRemovalCount { get; }

    Task<string?> UndoLastRemovalAsync(CancellationToken cancellationToken = default);

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

    Task SetTagsAsync(
        string downloadId,
        IReadOnlyList<string> tags,
        CancellationToken cancellationToken = default);

    Task SetArchivedAsync(
        string downloadId,
        bool archived,
        CancellationToken cancellationToken = default);

    Task RelinkAsync(
        string downloadId,
        string existingPath,
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
