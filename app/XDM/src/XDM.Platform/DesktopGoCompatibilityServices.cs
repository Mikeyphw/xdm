using System.Security.Cryptography;
using XDM.Core.Downloads;
using XDM.Core.Queues;
using XDM.Core.Scheduling;
using XDM.DownloadEngine;
using XDM.DownloadEngine.Aria2;
using XDM.DownloadEngine.Queues;

namespace XDM.Platform;

/// <summary>
/// Compatibility surface for older Avalonia features that still depend on IDownloadManager.
/// It never instantiates the legacy DownloadManager engine. Primary transfer verbs route to Go;
/// donor-only maintenance features fail explicitly instead of silently reviving old authority.
/// </summary>
public sealed class DesktopGoCompatibilityDownloadManager(DesktopGoProjectionClient go) : IDownloadManager
{
    private readonly DesktopGoProjectionClient _go = go ?? throw new ArgumentNullException(nameof(go));

    public event EventHandler<QueueRuntimeSnapshot>? QueueRuntimeChanged
    {
        add { }
        remove { }
    }

    public QueueRuntimeSnapshot QueueRuntime => QueueRuntimeSnapshot.Empty;
    public int UndoableRemovalCount => 0;

    public Task InitializeAsync(CancellationToken cancellationToken = default)
    {
        cancellationToken.ThrowIfCancellationRequested();
        return Task.CompletedTask;
    }

    public void FreezeAdmission()
    {
    }

    public Task<DownloadShutdownReport> PrepareForShutdownAsync(CancellationToken cancellationToken = default)
    {
        cancellationToken.ThrowIfCancellationRequested();
        DateTimeOffset now = DateTimeOffset.UtcNow;
        return Task.FromResult(new DownloadShutdownReport([], 0, 0, [], now, now));
    }

    public Task<string> AddAsync(DownloadRequest request, CancellationToken cancellationToken = default)
        => _go.AddDownloadAsync(request, cancellationToken);

    public Task<DownloadAdmissionPreview> PreviewAdmissionAsync(DownloadRequest request, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(request);
        cancellationToken.ThrowIfCancellationRequested();
        string fileName = request.ResolveFileName();
        string destination = Path.Combine(request.DestinationDirectory, fileName);
        return Task.FromResult(new DownloadAdmissionPreview(request.Source, fileName, destination, File.Exists(destination), false));
    }

    public async Task<IReadOnlyList<DownloadAdmissionPreview>> PreviewBatchAdmissionAsync(IReadOnlyList<DownloadRequest> requests, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(requests);
        List<DownloadAdmissionPreview> previews = new(requests.Count);
        foreach (DownloadRequest request in requests)
        {
            previews.Add(await PreviewAdmissionAsync(request, cancellationToken).ConfigureAwait(false));
        }
        return previews;
    }

    public async Task<DownloadVerificationResult> VerifyAsync(string downloadId, CancellationToken cancellationToken = default)
    {
        DesktopGoDownloadProjection item = RequireProjection(downloadId);
        if (!File.Exists(item.DestinationPath))
        {
            throw new FileNotFoundException("The projected download file does not exist.", item.DestinationPath);
        }
        await using FileStream stream = new(item.DestinationPath, FileMode.Open, FileAccess.Read, FileShare.Read, 128 * 1024, FileOptions.Asynchronous | FileOptions.SequentialScan);
        byte[] digest = await SHA256.HashDataAsync(stream, cancellationToken).ConfigureAwait(false);
        string checksum = Convert.ToHexString(digest).ToLowerInvariant();
        return new DownloadVerificationResult(downloadId, item.DestinationPath, "SHA-256", checksum, null, true, stream.Length, "Local integrity hash calculated by the Go-authority compatibility surface.", checksum, null, null, null, true);
    }

    public async Task<DownloadRepairResult> RepairAsync(string downloadId, CancellationToken cancellationToken = default)
    {
        await _go.ExecuteDownloadActionAsync("retry", downloadId, cancellationToken).ConfigureAwait(false);
        return new DownloadRepairResult(downloadId, true, null, "Retry requested through Go authority.");
    }

    public Task<DownloadRepairResult> RestartFromZeroAsync(string downloadId, CancellationToken cancellationToken = default)
        => RepairAsync(downloadId, cancellationToken);

    public Task<DownloadChecksumWorkflowState> GetChecksumWorkflowAsync(string downloadId, CancellationToken cancellationToken = default)
    {
        cancellationToken.ThrowIfCancellationRequested();
        DesktopGoDownloadProjection item = RequireProjection(downloadId);
        return Task.FromResult(DownloadChecksumWorkflowState.Empty(item.DestinationPath, downloadId));
    }

    public Task SetExpectedChecksumsAsync(string downloadId, string? expectedSha256, string? expectedSha512, CancellationToken cancellationToken = default)
        => RemovedFeature("checksum editing", cancellationToken);

    public Task<IReadOnlyList<string>> AddMetalinkAsync(Stream stream, string destinationDirectory, string? queueId = null, CancellationToken cancellationToken = default)
        => RemovedFeature<IReadOnlyList<string>>("legacy Metalink admission", cancellationToken);

    public Task PauseAsync(string downloadId, CancellationToken cancellationToken = default)
        => _go.ExecuteDownloadActionAsync("pause", downloadId, cancellationToken);

    public Task ResumeAsync(string downloadId, CancellationToken cancellationToken = default)
        => _go.ExecuteDownloadActionAsync("resume", downloadId, cancellationToken);

    public Task CancelAsync(string downloadId, CancellationToken cancellationToken = default)
        => _go.ExecuteDownloadActionAsync("cancel", downloadId, cancellationToken);

    public Task RetryAsync(string downloadId, CancellationToken cancellationToken = default)
        => _go.ExecuteDownloadActionAsync("retry", downloadId, cancellationToken);

    public Task SetPriorityAsync(string downloadId, DownloadPriority priority, CancellationToken cancellationToken = default)
        => RemovedFeature("legacy queue priority mutation", cancellationToken);

    public Task RemoveAsync(string downloadId, bool deletePartialFile = false, CancellationToken cancellationToken = default)
        => RemovedFeature("legacy history mutation", cancellationToken);

    public Task<string?> UndoLastRemovalAsync(CancellationToken cancellationToken = default)
        => Task.FromResult<string?>(null);

    public Task DeleteAsync(string downloadId, DownloadDeletionScope scope, CancellationToken cancellationToken = default)
        => RemovedFeature("legacy history/file deletion transaction", cancellationToken);

    public Task RelocateAsync(string downloadId, string destinationPath, bool overwrite = false, CancellationToken cancellationToken = default)
        => RemovedFeature("legacy relocation transaction", cancellationToken);

    public Task<string> RedownloadAsync(string downloadId, DuplicateFileBehavior duplicateBehavior = DuplicateFileBehavior.AutoRename, CancellationToken cancellationToken = default)
        => RemovedFeature<string>("legacy redownload transaction", cancellationToken);

    public Task RefreshSourceAsync(string downloadId, Uri source, Uri? sourcePage = null, CancellationToken cancellationToken = default)
        => RemovedFeature("legacy source mutation", cancellationToken);

    public Task SetTagsAsync(string downloadId, IReadOnlyList<string> tags, CancellationToken cancellationToken = default)
        => RemovedFeature("legacy tag mutation", cancellationToken);

    public Task SetArchivedAsync(string downloadId, bool archived, CancellationToken cancellationToken = default)
        => RemovedFeature("legacy archive mutation", cancellationToken);

    public Task RelinkAsync(string downloadId, string existingPath, CancellationToken cancellationToken = default)
        => RemovedFeature("legacy relink mutation", cancellationToken);

    public Task<int> PruneHistoryAsync(CancellationToken cancellationToken = default)
        => RemovedFeature<int>("legacy history pruning", cancellationToken);

    public Task StartQueueAsync(string queueId, CancellationToken cancellationToken = default)
        => RemovedFeature("legacy queue scheduler authority", cancellationToken);

    public Task StopQueueAsync(string queueId, CancellationToken cancellationToken = default)
        => RemovedFeature("legacy queue scheduler authority", cancellationToken);

    public Task MoveToQueueAsync(string downloadId, string queueId, int? queueOrder = null, CancellationToken cancellationToken = default)
        => RemovedFeature("legacy queue mutation", cancellationToken);

    private DesktopGoDownloadProjection RequireProjection(string id)
        => _go.CurrentDownloads.FirstOrDefault(item => string.Equals(item.Id, id, StringComparison.Ordinal))
            ?? throw new KeyNotFoundException($"Go desktop projection does not contain download '{id}'.");

    private static Task RemovedFeature(string feature, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        return Task.FromException(new NotSupportedException($"{feature} was removed with the XGO-80 legacy-engine cutover."));
    }

    private static Task<T> RemovedFeature<T>(string feature, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        return Task.FromException<T>(new NotSupportedException($"{feature} was removed with the XGO-80 legacy-engine cutover."));
    }
}

public sealed class DesktopGoRecoveryCoordinator : IDownloadRecoveryCoordinator
{
    public event EventHandler? Changed
    {
        add { }
        remove { }
    }

    public IReadOnlyList<DownloadRecoveryCandidate> Current => [];

    public Task ScanAsync(bool previousSessionWasUnclean, IReadOnlyCollection<string>? previousActiveDownloadIds = null, bool? checkpointFlushSucceeded = null, CancellationToken cancellationToken = default)
    {
        cancellationToken.ThrowIfCancellationRequested();
        return Task.CompletedTask;
    }

    public Task<DownloadRecoveryCandidate> ValidateAsync(string candidateId, CancellationToken cancellationToken = default)
        => Task.FromException<DownloadRecoveryCandidate>(new NotSupportedException("Legacy C# recovery authority was removed by XGO-80; recovery is owned by Go durable state."));

    public Task DismissAsync(string candidateId, bool persist = false, CancellationToken cancellationToken = default)
    {
        cancellationToken.ThrowIfCancellationRequested();
        return Task.CompletedTask;
    }

    public void MarkResumeStarted(string candidateId)
    {
    }
}

public sealed class DesktopGoQueueSchedulerRuntime : IQueueSchedulerRuntime
{
    public bool IsRunning => false;
    public DateTimeOffset? NextEvaluationAt => null;
    public SchedulerRuntimeSnapshot Current => SchedulerRuntimeSnapshot.Empty;

    public event EventHandler<SchedulerRuntimeSnapshot>? Changed
    {
        add { }
        remove { }
    }

    public Task InitializeAsync(CancellationToken cancellationToken = default)
    {
        cancellationToken.ThrowIfCancellationRequested();
        return Task.CompletedTask;
    }

    public Task EvaluateAsync(CancellationToken cancellationToken = default)
    {
        cancellationToken.ThrowIfCancellationRequested();
        return Task.CompletedTask;
    }

    public bool CancelPendingAction() => false;
    public void Dispose() { }
    public ValueTask DisposeAsync() => ValueTask.CompletedTask;
}

public sealed class DesktopGoAria2CompatibilityService : IAria2Service
{
    public Aria2ServiceSnapshot Current => Aria2ServiceSnapshot.Disabled;

    public event EventHandler<Aria2ServiceSnapshot>? Changed
    {
        add { }
        remove { }
    }

    public Task InitializeAsync(CancellationToken cancellationToken = default) => Completed(cancellationToken);
    public Task ConfigureAsync(XDM.Core.Settings.Aria2IntegrationSettings settings, CancellationToken cancellationToken = default) => Completed(cancellationToken);
    public Task StartManagedProcessAsync(CancellationToken cancellationToken = default) => Removed(cancellationToken);
    public Task StopManagedProcessAsync(CancellationToken cancellationToken = default) => Completed(cancellationToken);
    public Task RefreshAsync(CancellationToken cancellationToken = default) => Completed(cancellationToken);
    public Task<string> AddAsync(Aria2AddRequest request, CancellationToken cancellationToken = default) => Removed<string>(cancellationToken);
    public Task<Aria2TaskSnapshot?> GetTaskAsync(string gid, CancellationToken cancellationToken = default) => Task.FromResult<Aria2TaskSnapshot?>(null);
    public Task ChangeOptionsAsync(string gid, Aria2RuntimeOptions options, CancellationToken cancellationToken = default) => Removed(cancellationToken);
    public Task PauseAsync(string gid, CancellationToken cancellationToken = default) => Removed(cancellationToken);
    public Task ResumeAsync(string gid, CancellationToken cancellationToken = default) => Removed(cancellationToken);
    public Task RemoveAsync(string gid, CancellationToken cancellationToken = default) => Removed(cancellationToken);

    private static Task Completed(CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        return Task.CompletedTask;
    }

    private static Task Removed(CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        return Task.FromException(new NotSupportedException("The stateful desktop aria2 owner was removed by XGO-80; Go dispatches stateless aria2 execution through the desktop platform broker."));
    }

    private static Task<T> Removed<T>(CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        return Task.FromException<T>(new NotSupportedException("The stateful desktop aria2 owner was removed by XGO-80."));
    }
}
