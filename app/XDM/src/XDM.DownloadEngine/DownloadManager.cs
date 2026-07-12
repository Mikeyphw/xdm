using System.Collections.Concurrent;
using System.Diagnostics;
using System.Net;
using System.Net.Http.Headers;
using System.Text;
using Microsoft.Extensions.Logging;
using XDM.Core.Downloads;
using XDM.Core.Persistence;
using XDM.Core.Queues;
using XDM.Core.Settings;
using XDM.Core.State;
using XDM.DownloadEngine.Logging;

namespace XDM.DownloadEngine;

public sealed class DownloadManager : IDownloadManager, IDisposable
{
    private const int BufferSize = 64 * 1024;
    private const long DiskSafetyMarginBytes = 8L * 1024 * 1024;
    private static readonly TimeSpan ProgressInterval = TimeSpan.FromMilliseconds(150);
    private static readonly TimeSpan PersistenceInterval = TimeSpan.FromSeconds(2);

    private readonly HttpClient _httpClient;
    private readonly IApplicationState _applicationState;
    private readonly IDownloadHistoryStore _historyStore;
    private readonly ILogger<DownloadManager> _logger;
    private readonly IDiskSpaceProvider _diskSpaceProvider;
    private readonly DownloadRetryPolicy _retryPolicy;
    private readonly SegmentedDownloadExecutor _segmentedExecutor;
    private readonly ISettingsService _settingsService;
    private readonly SemaphoreSlim _concurrencyGate;
    private readonly ConcurrentDictionary<string, byte> _activeQueues = new(StringComparer.Ordinal);
    private readonly ConcurrentDictionary<string, SemaphoreSlim> _queueGates = new(StringComparer.Ordinal);
    private readonly ConcurrentDictionary<string, DownloadSession> _sessions = new(StringComparer.Ordinal);
    private readonly SemaphoreSlim _persistenceGate = new(1, 1);
    private DateTimeOffset _lastPersistence = DateTimeOffset.MinValue;
    private bool _disposed;

    public event EventHandler<QueueRuntimeSnapshot>? QueueRuntimeChanged;

    public QueueRuntimeSnapshot QueueRuntime => CreateQueueRuntimeSnapshot();

    public DownloadManager(
        HttpClient httpClient,
        IApplicationState applicationState,
        IDownloadHistoryStore historyStore,
        ISettingsService settingsService,
        ILogger<DownloadManager> logger)
        : this(
            httpClient,
            applicationState,
            historyStore,
            settingsService,
            logger,
            new SystemDiskSpaceProvider(),
            CreateRetryPolicy(settingsService.Current),
            CreateSegmentedOptions(settingsService.Current))
    {
    }

    public DownloadManager(
        HttpClient httpClient,
        IApplicationState applicationState,
        IDownloadHistoryStore historyStore,
        ISettingsService settingsService,
        ILogger<DownloadManager> logger,
        IDiskSpaceProvider diskSpaceProvider,
        DownloadRetryPolicy retryPolicy,
        SegmentedDownloadOptions? segmentedOptions = null)
    {
        _httpClient = httpClient;
        _applicationState = applicationState;
        _historyStore = historyStore;
        _settingsService = settingsService;
        _logger = logger;
        _diskSpaceProvider = diskSpaceProvider;
        _retryPolicy = retryPolicy;
        _segmentedExecutor = new SegmentedDownloadExecutor(
            httpClient,
            diskSpaceProvider,
            retryPolicy,
            segmentedOptions ?? new SegmentedDownloadOptions());
        _concurrencyGate = new SemaphoreSlim(settingsService.Current.MaxConcurrentDownloads);
        IReadOnlyList<DownloadQueueDefinition> configuredQueues = settingsService.Current.Queues;
        string defaultQueueId = configuredQueues.Count > 0 ? configuredQueues[0].Id : "default";
        _activeQueues.TryAdd(defaultQueueId, 0);
    }

    public async Task InitializeAsync(CancellationToken cancellationToken = default)
    {
        ThrowIfDisposed();
        IReadOnlyList<PersistedDownload> loaded = await _historyStore
            .LoadAsync(cancellationToken)
            .ConfigureAwait(false);
        IReadOnlyList<PersistedDownload> persisted = HistoryRetentionPolicy.Apply(
            loaded,
            _settingsService.Current.History ?? HistoryRetentionSettings.Default,
            DateTimeOffset.UtcNow);
        if (persisted.Count != loaded.Count)
        {
            await _historyStore.SaveAsync(persisted.ToArray(), cancellationToken).ConfigureAwait(false);
        }

        IReadOnlyList<DownloadQueueDefinition> configuredQueues = _settingsService.Current.Queues;
        string fallbackQueueId = configuredQueues.Count > 0 ? configuredQueues[0].Id : "default";
        List<DownloadSnapshot> restoredSnapshots = new(persisted.Count);
        foreach (PersistedDownload item in persisted)
        {
            string restoredMethod = string.Equals(item.Method, "POST", StringComparison.OrdinalIgnoreCase)
                ? "POST"
                : "GET";
            DownloadState restoredState = item.State is DownloadState.Connecting
                or DownloadState.Downloading
                or DownloadState.Finalizing
                    ? DownloadState.Paused
                    : item.State;
            string? restoredError = item.ErrorMessage;
            if (restoredMethod == "POST"
                && restoredState is (DownloadState.Queued or DownloadState.Paused or DownloadState.Failed))
            {
                restoredState = DownloadState.Failed;
                restoredError = "Captured POST data is intentionally not persisted and cannot be replayed after restart.";
            }

            DownloadSession session = new(
                item.Id,
                item.Source,
                item.DestinationPath,
                restoredState,
                item.DownloadedBytes,
                item.TotalBytes,
                restoredError,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                string.IsNullOrWhiteSpace(item.QueueId) ? fallbackQueueId : item.QueueId,
                item.CategoryId,
                Math.Max(0, item.QueueOrder),
                item.EntityTag,
                item.LastModified,
                item.ConnectionCount,
                restoredMethod,
                null,
                null,
                item.Priority,
                item.SourcePage,
                item.UpdatedAt);

            try
            {
                RecoverInterruptedFinalization(session);
            }
            catch (IOException exception)
            {
                session.State = DownloadState.Failed;
                session.ErrorMessage = $"Could not recover interrupted finalization: {exception.Message}";
            }
            catch (UnauthorizedAccessException exception)
            {
                session.State = DownloadState.Failed;
                session.ErrorMessage = $"Could not recover interrupted finalization: {exception.Message}";
            }

            _sessions[item.Id] = session;
            restoredSnapshots.Add(CreateSnapshot(session));
        }

        _applicationState.ReplaceDownloads(restoredSnapshots);

        foreach (DownloadSession session in _sessions.Values
            .Where(session => session.State == DownloadState.Queued && IsQueueActive(session.QueueId))
            .OrderByDescending(static session => session.Priority)
            .ThenBy(static session => session.QueueOrder))
        {
            Start(session);
        }

        PublishQueueRuntime();
    }

    public Task<string> AddAsync(DownloadRequest request, CancellationToken cancellationToken = default)
    {
        ThrowIfDisposed();
        ArgumentNullException.ThrowIfNull(request);
        cancellationToken.ThrowIfCancellationRequested();

        if (!request.Source.IsAbsoluteUri || request.Source.Scheme is not ("http" or "https"))
        {
            throw new ArgumentException("Only absolute HTTP and HTTPS URLs are supported.", nameof(request));
        }

        string method = string.IsNullOrWhiteSpace(request.Method) ? "GET" : request.Method.Trim().ToUpperInvariant();
        if (method is not ("GET" or "POST"))
        {
            throw new ArgumentException("Only GET and POST download requests are supported.", nameof(request));
        }

        if (request.RequestBody is { Length: > 16 * 1024 })
        {
            throw new ArgumentException("The request body exceeds 16 KiB.", nameof(request));
        }

        if (request.RequestBody is not null && method != "POST")
        {
            throw new ArgumentException("Request bodies are only supported for POST downloads.", nameof(request));
        }

        if (!string.IsNullOrWhiteSpace(request.RequestBodyContentType) &&
            (method != "POST" || !MediaTypeHeaderValue.TryParse(request.RequestBodyContentType, out _)))
        {
            throw new ArgumentException("The request body content type is invalid or is not associated with a POST request.", nameof(request));
        }

        ArgumentException.ThrowIfNullOrWhiteSpace(request.DestinationDirectory);
        DownloadBehaviorSettings behavior = _settingsService.Current.DownloadBehavior
            ?? DownloadBehaviorSettings.Default;
        if (!Directory.Exists(request.DestinationDirectory))
        {
            if (!behavior.CreateDestinationDirectory)
            {
                throw new DirectoryNotFoundException(
                    $"The destination directory does not exist: {request.DestinationDirectory}");
            }
            Directory.CreateDirectory(request.DestinationDirectory);
        }

        string fileName = request.ResolveFileName();
        string destinationPath = ResolveDestinationPath(request, fileName);
        if (request.DuplicateBehavior == DuplicateFileBehavior.Overwrite)
        {
            string overwritePartialPath = GetPartialPath(destinationPath);
            if (File.Exists(overwritePartialPath))
            {
                File.Delete(overwritePartialPath);
            }

            string overwriteMarkerPath = GetFinalizationMarkerPath(destinationPath);
            if (File.Exists(overwriteMarkerPath))
            {
                File.Delete(overwriteMarkerPath);
            }

            string overwriteSegmentDirectory = SegmentedDownloadExecutor.GetSegmentDirectory(destinationPath);
            if (Directory.Exists(overwriteSegmentDirectory))
            {
                Directory.Delete(overwriteSegmentDirectory, recursive: true);
            }
        }

        IReadOnlyList<DownloadQueueDefinition> configuredQueues = _settingsService.Current.Queues;
        string fallbackQueueId = configuredQueues.Count > 0 ? configuredQueues[0].Id : "default";
        string queueId = string.IsNullOrWhiteSpace(request.QueueId)
            ? fallbackQueueId
            : request.QueueId;
        int queueOrder = _sessions.Values
            .Where(session => string.Equals(session.QueueId, queueId, StringComparison.Ordinal))
            .Select(static session => session.QueueOrder)
            .DefaultIfEmpty(-1)
            .Max() + 1;

        DownloadSession session = new(
            Guid.NewGuid().ToString("N"),
            request.Source,
            destinationPath,
            DownloadState.Queued,
            0,
            null,
            null,
            request.Headers,
            request.Username,
            request.Password,
            request.Cookie,
            request.Referer,
            request.UserAgent,
            request.SpeedLimitBytesPerSecond,
            queueId,
            request.CategoryId,
            queueOrder,
            null,
            null,
            method == "GET" ? Math.Clamp(request.ConnectionCount, 1, 32) : 1,
            method,
            request.RequestBody?.ToArray(),
            request.RequestBodyContentType,
            request.Priority,
            request.SourcePage,
            DateTimeOffset.UtcNow);

        if (!_sessions.TryAdd(session.Id, session))
        {
            throw new InvalidOperationException("Could not register the new download.");
        }

        Publish(session, forcePersist: true);
        if (IsQueueActive(session.QueueId))
        {
            Start(session);
        }

        PublishQueueRuntime();
        return Task.FromResult(session.Id);
    }

    public Task PauseAsync(string downloadId, CancellationToken cancellationToken = default)
    {
        DownloadSession session = GetSession(downloadId);
        cancellationToken.ThrowIfCancellationRequested();

        lock (session.Sync)
        {
            if (session.State is DownloadState.Connecting or DownloadState.Downloading)
            {
                session.PauseRequested = true;
                session.OperationCancellation?.Cancel();
            }
            else if (session.State == DownloadState.Queued)
            {
                session.State = DownloadState.Paused;
                Publish(session, forcePersist: true);
            }
        }

        return Task.CompletedTask;
    }

    public Task ResumeAsync(string downloadId, CancellationToken cancellationToken = default)
    {
        DownloadSession session = GetSession(downloadId);
        cancellationToken.ThrowIfCancellationRequested();

        lock (session.Sync)
        {
            if (session.State is not (DownloadState.Paused or DownloadState.Cancelled or DownloadState.Failed))
            {
                return Task.CompletedTask;
            }

            if (session.Method != "GET" && session.RequestBody is null)
            {
                session.State = DownloadState.Failed;
                session.ErrorMessage = "Captured POST data is no longer available and cannot be replayed.";
                Publish(session, forcePersist: true);
                return Task.CompletedTask;
            }

            session.PauseRequested = false;
            session.ErrorMessage = null;
        }

        Start(session);
        return Task.CompletedTask;
    }

    public Task CancelAsync(string downloadId, CancellationToken cancellationToken = default)
    {
        DownloadSession session = GetSession(downloadId);
        cancellationToken.ThrowIfCancellationRequested();

        lock (session.Sync)
        {
            session.PauseRequested = false;
            session.OperationCancellation?.Cancel();

            if (session.State is DownloadState.Queued or DownloadState.Paused or DownloadState.Failed)
            {
                session.State = DownloadState.Cancelled;
                Publish(session, forcePersist: true);
            }
        }

        return Task.CompletedTask;
    }

    public Task RetryAsync(string downloadId, CancellationToken cancellationToken = default)
        => ResumeAsync(downloadId, cancellationToken);

    public async Task SetPriorityAsync(
        string downloadId,
        DownloadPriority priority,
        CancellationToken cancellationToken = default)
    {
        DownloadSession session = GetSession(downloadId);
        cancellationToken.ThrowIfCancellationRequested();
        lock (session.Sync)
        {
            session.Priority = priority;
        }

        _applicationState.UpsertDownload(CreateSnapshot(session));
        await PersistAsync(force: true, cancellationToken).ConfigureAwait(false);
    }

    public Task RemoveAsync(
        string downloadId,
        bool deletePartialFile = false,
        CancellationToken cancellationToken = default)
        => DeleteAsync(
            downloadId,
            deletePartialFile
                ? DownloadDeletionScope.HistoryAndPartialData
                : DownloadDeletionScope.HistoryOnly,
            cancellationToken);

    public async Task DeleteAsync(
        string downloadId,
        DownloadDeletionScope scope,
        CancellationToken cancellationToken = default)
    {
        DownloadSession session = GetSession(downloadId);
        cancellationToken.ThrowIfCancellationRequested();

        lock (session.Sync)
        {
            session.PauseRequested = false;
            session.OperationCancellation?.Cancel();
        }

        if (session.ActiveTask is { IsCompleted: false } activeTask)
        {
            try
            {
                await activeTask.WaitAsync(cancellationToken).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
            {
                // The operation was cancelled above so its files can be removed safely.
            }
        }

        if (scope != DownloadDeletionScope.HistoryOnly)
        {
            DeleteTransferArtifacts(session.DestinationPath);
        }

        if (scope == DownloadDeletionScope.HistoryAndDownloadedFile
            && File.Exists(session.DestinationPath))
        {
            File.Delete(session.DestinationPath);
        }

        _sessions.TryRemove(downloadId, out _);
        _applicationState.RemoveDownload(downloadId);
        await PersistAsync(force: true, cancellationToken).ConfigureAwait(false);
    }

    public async Task RelocateAsync(
        string downloadId,
        string destinationPath,
        bool overwrite = false,
        CancellationToken cancellationToken = default)
    {
        DownloadSession session = GetSession(downloadId);
        ArgumentException.ThrowIfNullOrWhiteSpace(destinationPath);
        cancellationToken.ThrowIfCancellationRequested();

        string sourcePath;
        lock (session.Sync)
        {
            if (session.State != DownloadState.Completed)
            {
                throw new InvalidOperationException("Only completed downloads can be moved or renamed.");
            }

            sourcePath = session.DestinationPath;
        }

        if (!File.Exists(sourcePath))
        {
            throw new FileNotFoundException("The completed download file no longer exists.", sourcePath);
        }

        string targetPath = Path.GetFullPath(destinationPath);
        if (string.Equals(Path.GetFullPath(sourcePath), targetPath, StringComparison.Ordinal))
        {
            return;
        }

        bool destinationInUse = _sessions.Values.Any(other =>
            !ReferenceEquals(other, session)
            && string.Equals(other.DestinationPath, targetPath, StringComparison.OrdinalIgnoreCase));
        if (destinationInUse)
        {
            throw new IOException($"Another download already uses the destination path: {targetPath}");
        }

        string? targetDirectory = Path.GetDirectoryName(targetPath);
        if (string.IsNullOrWhiteSpace(targetDirectory))
        {
            throw new DirectoryNotFoundException("The destination directory could not be resolved.");
        }

        Directory.CreateDirectory(targetDirectory);
        if (File.Exists(targetPath) && !overwrite)
        {
            throw new IOException($"The destination file already exists: {targetPath}");
        }

        await MoveFileAsync(sourcePath, targetPath, overwrite, cancellationToken).ConfigureAwait(false);
        lock (session.Sync)
        {
            session.DestinationPath = targetPath;
            session.ErrorMessage = null;
            session.UpdatedAt = DateTimeOffset.UtcNow;
        }

        _applicationState.UpsertDownload(CreateSnapshot(session));
        await PersistAsync(force: true, cancellationToken).ConfigureAwait(false);
    }

    public Task<string> RedownloadAsync(
        string downloadId,
        DuplicateFileBehavior duplicateBehavior = DuplicateFileBehavior.AutoRename,
        CancellationToken cancellationToken = default)
    {
        DownloadSession session = GetSession(downloadId);
        DownloadRequest request;
        lock (session.Sync)
        {
            if (session.Method != "GET")
            {
                throw new InvalidOperationException("Only GET downloads can be safely re-downloaded from history.");
            }

            request = new DownloadRequest(
                session.Source,
                Path.GetDirectoryName(session.DestinationPath)
                    ?? _settingsService.Current.DefaultDownloadDirectory,
                Path.GetFileName(session.DestinationPath),
                session.Headers,
                session.Username,
                session.Password,
                session.Cookie,
                session.Referer,
                session.UserAgent,
                session.QueueId,
                session.CategoryId,
                session.SpeedLimitBytesPerSecond,
                duplicateBehavior,
                session.ConnectionCount,
                Priority: session.Priority,
                SourcePage: session.SourcePage);
        }

        return AddAsync(request, cancellationToken);
    }

    public async Task RefreshSourceAsync(
        string downloadId,
        Uri source,
        Uri? sourcePage = null,
        CancellationToken cancellationToken = default)
    {
        DownloadSession session = GetSession(downloadId);
        ValidateHttpUri(source, nameof(source));
        if (sourcePage is not null)
        {
            ValidateHttpUri(sourcePage, nameof(sourcePage));
        }

        cancellationToken.ThrowIfCancellationRequested();
        lock (session.Sync)
        {
            if (session.Method != "GET")
            {
                throw new InvalidOperationException("Only GET downloads can refresh their source URL.");
            }

            if (session.State == DownloadState.Completed)
            {
                throw new InvalidOperationException("Completed downloads should be queued again with Re-download.");
            }

            if (session.State is DownloadState.Connecting or DownloadState.Downloading or DownloadState.Finalizing)
            {
                throw new InvalidOperationException("Pause or cancel the download before refreshing its URL.");
            }

            session.Source = source;
            session.SourcePage = sourcePage;
            session.EntityTag = null;
            session.LastModified = null;
            session.ErrorMessage = null;
            if (session.State is DownloadState.Failed or DownloadState.Cancelled)
            {
                session.State = DownloadState.Paused;
            }
            session.UpdatedAt = DateTimeOffset.UtcNow;
        }

        _applicationState.UpsertDownload(CreateSnapshot(session));
        await PersistAsync(force: true, cancellationToken).ConfigureAwait(false);
    }

    public async Task<int> PruneHistoryAsync(CancellationToken cancellationToken = default)
    {
        cancellationToken.ThrowIfCancellationRequested();
        PersistedDownload[] current = _sessions.Values
            .Select(CreatePersistedDownload)
            .ToArray();
        IReadOnlyList<PersistedDownload> retained = HistoryRetentionPolicy.Apply(
            current,
            _settingsService.Current.History ?? HistoryRetentionSettings.Default,
            DateTimeOffset.UtcNow);
        HashSet<string> retainedIds = retained
            .Select(static item => item.Id)
            .ToHashSet(StringComparer.Ordinal);
        string[] removeIds = current
            .Where(item => !retainedIds.Contains(item.Id))
            .Select(static item => item.Id)
            .ToArray();
        foreach (string id in removeIds)
        {
            _sessions.TryRemove(id, out _);
            _applicationState.RemoveDownload(id);
        }

        if (removeIds.Length > 0)
        {
            await _historyStore.SaveAsync(retained.ToArray(), cancellationToken).ConfigureAwait(false);
        }

        return removeIds.Length;
    }

    public Task StartQueueAsync(string queueId, CancellationToken cancellationToken = default)
    {
        ThrowIfDisposed();
        ArgumentException.ThrowIfNullOrWhiteSpace(queueId);
        cancellationToken.ThrowIfCancellationRequested();

        _activeQueues.TryAdd(queueId, 0);
        foreach (DownloadSession session in _sessions.Values
            .Where(session => string.Equals(session.QueueId, queueId, StringComparison.Ordinal)
                && session.State == DownloadState.Queued)
            .OrderByDescending(static session => session.Priority)
            .ThenBy(static session => session.QueueOrder))
        {
            Start(session);
        }

        PublishQueueRuntime();
        return Task.CompletedTask;
    }

    public Task StopQueueAsync(string queueId, CancellationToken cancellationToken = default)
    {
        ThrowIfDisposed();
        ArgumentException.ThrowIfNullOrWhiteSpace(queueId);
        cancellationToken.ThrowIfCancellationRequested();

        _activeQueues.TryRemove(queueId, out _);
        foreach (DownloadSession session in _sessions.Values
            .Where(session => string.Equals(session.QueueId, queueId, StringComparison.Ordinal)))
        {
            lock (session.Sync)
            {
                if (session.State is DownloadState.Connecting or DownloadState.Downloading)
                {
                    session.QueueStopRequested = true;
                    session.PauseRequested = true;
                    session.OperationCancellation?.Cancel();
                }
            }
        }

        PublishQueueRuntime();
        return Task.CompletedTask;
    }

    public async Task MoveToQueueAsync(
        string downloadId,
        string queueId,
        int? queueOrder = null,
        CancellationToken cancellationToken = default)
    {
        DownloadSession session = GetSession(downloadId);
        ArgumentException.ThrowIfNullOrWhiteSpace(queueId);
        cancellationToken.ThrowIfCancellationRequested();

        string previousQueueId;
        lock (session.Sync)
        {
            previousQueueId = session.QueueId;
        }

        List<DownloadSession> target = _sessions.Values
            .Where(item => !ReferenceEquals(item, session)
                && string.Equals(item.QueueId, queueId, StringComparison.Ordinal))
            .OrderBy(static item => item.QueueOrder)
            .ThenBy(static item => item.Id, StringComparer.Ordinal)
            .ToList();
        int targetIndex = Math.Clamp(queueOrder ?? target.Count, 0, target.Count);
        target.Insert(targetIndex, session);

        for (int index = 0; index < target.Count; index++)
        {
            lock (target[index].Sync)
            {
                target[index].QueueId = queueId;
                target[index].QueueOrder = index;
            }

            _applicationState.UpsertDownload(CreateSnapshot(target[index]));
        }

        if (!string.Equals(previousQueueId, queueId, StringComparison.Ordinal))
        {
            NormalizeQueueOrder(previousQueueId);
            foreach (DownloadSession previous in _sessions.Values
                .Where(item => string.Equals(item.QueueId, previousQueueId, StringComparison.Ordinal)))
            {
                _applicationState.UpsertDownload(CreateSnapshot(previous));
            }
        }

        PublishQueueRuntime();
        await PersistAsync(force: true, cancellationToken).ConfigureAwait(false);
    }

    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }

        _disposed = true;
        foreach (DownloadSession session in _sessions.Values)
        {
            lock (session.Sync)
            {
                session.OperationCancellation?.Cancel();
                session.OperationCancellation?.Dispose();
                session.OperationCancellation = null;
            }
        }

        foreach (SemaphoreSlim gate in _queueGates.Values)
        {
            gate.Dispose();
        }

        _persistenceGate.Dispose();
        _concurrencyGate.Dispose();
    }

    private void Start(DownloadSession session)
    {
        if (!IsQueueActive(session.QueueId))
        {
            lock (session.Sync)
            {
                session.State = DownloadState.Queued;
            }

            Publish(session, forcePersist: false);
            return;
        }

        CancellationTokenSource operationCancellation;
        lock (session.Sync)
        {
            if (session.ActiveTask is { IsCompleted: false })
            {
                return;
            }

            session.OperationCancellation?.Dispose();
            operationCancellation = new CancellationTokenSource();
            session.OperationCancellation = operationCancellation;
            session.State = DownloadState.Connecting;
            session.PauseRequested = false;
            session.ErrorMessage = null;
            Publish(session, forcePersist: false);
            session.ActiveTask = Task.Run(
                () => RunDownloadAsync(session, operationCancellation.Token),
                CancellationToken.None);
        }
    }

    private async Task RunDownloadAsync(DownloadSession session, CancellationToken cancellationToken)
    {
        string partialPath = GetPartialPath(session.DestinationPath);

        bool concurrencyAcquired = false;
        bool queueConcurrencyAcquired = false;
        SemaphoreSlim? queueGate = null;
        try
        {
            await _concurrencyGate.WaitAsync(cancellationToken).ConfigureAwait(false);
            concurrencyAcquired = true;
            queueGate = GetQueueGate(session.QueueId);
            await queueGate.WaitAsync(cancellationToken).ConfigureAwait(false);
            queueConcurrencyAcquired = true;

            int maximumAttempts = session.Method == "GET" ? _retryPolicy.MaximumAttempts : 1;
            for (int attempt = 1; attempt <= maximumAttempts; attempt++)
            {
                try
                {
                    await ExecuteDownloadAttemptAsync(session, partialPath, cancellationToken).ConfigureAwait(false);
                    return;
                }
                catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
                {
                    throw;
                }
                catch (Exception exception) when (
                    DownloadRetryPolicy.IsTransient(exception)
                    && attempt < maximumAttempts)
                {
                    TimeSpan delay = _retryPolicy.GetDelay(attempt);
                    lock (session.Sync)
                    {
                        session.State = DownloadState.Connecting;
                        session.BytesPerSecond = 0;
                        session.ErrorMessage = $"Temporary failure. Retrying in {delay.TotalSeconds:0.0}s.";
                    }

                    Publish(session, forcePersist: true);
                    DownloadEngineLog.DownloadRetrying(
                        _logger,
                        session.Id,
                        attempt + 1,
                        maximumAttempts,
                        delay.TotalMilliseconds,
                        exception.Message);
                    await Task.Delay(delay, cancellationToken).ConfigureAwait(false);
                }
            }
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            lock (session.Sync)
            {
                session.State = session.QueueStopRequested
                    ? DownloadState.Queued
                    : session.PauseRequested
                        ? DownloadState.Paused
                        : DownloadState.Cancelled;
                session.QueueStopRequested = false;
                session.BytesPerSecond = 0;
            }

            Publish(session, forcePersist: true);
        }
        catch (HttpRequestException exception)
        {
            Fail(session, exception);
        }
        catch (IOException exception)
        {
            Fail(session, exception);
        }
        catch (UnauthorizedAccessException exception)
        {
            Fail(session, exception);
        }
        catch (InvalidOperationException exception)
        {
            Fail(session, exception);
        }
        finally
        {
            if (queueConcurrencyAcquired)
            {
                queueGate!.Release();
            }

            if (concurrencyAcquired)
            {
                _concurrencyGate.Release();
            }

            PublishQueueRuntime();
        }
    }

    private async Task ExecuteDownloadAttemptAsync(
        DownloadSession session,
        string partialPath,
        CancellationToken cancellationToken)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(session.DestinationPath)!);
        long existingLength = File.Exists(partialPath) ? new FileInfo(partialPath).Length : 0;
        if (session.Method != "GET" && existingLength > 0)
        {
            File.Delete(partialPath);
            existingLength = 0;
        }

        if (session.Method == "GET" && existingLength == 0 && session.ConnectionCount > 1)
        {
            SegmentedDownloadContext context = new(
                session.Source,
                session.DestinationPath,
                session.Headers,
                session.Username,
                session.Password,
                session.Cookie,
                session.Referer,
                session.UserAgent,
                session.ConnectionCount,
                ResolveSpeedLimit(session));
            DateTimeOffset lastSegmentProgress = DateTimeOffset.MinValue;
            SegmentedDownloadResult? segmentedResult = await _segmentedExecutor.TryDownloadAsync(
                context,
                (downloaded, total, speed) =>
                {
                    lock (session.Sync)
                    {
                        session.DownloadedBytes = downloaded;
                        session.TotalBytes = total;
                        session.BytesPerSecond = speed;
                        session.State = DownloadState.Downloading;
                        session.ErrorMessage = null;
                    }

                    DateTimeOffset now = DateTimeOffset.UtcNow;
                    if (now - lastSegmentProgress >= ProgressInterval || downloaded == total)
                    {
                        lastSegmentProgress = now;
                        Publish(session, forcePersist: downloaded == total);
                    }

                    return ValueTask.CompletedTask;
                },
                cancellationToken).ConfigureAwait(false);
            if (segmentedResult is not null)
            {
                lock (session.Sync)
                {
                    session.DownloadedBytes = segmentedResult.TotalBytes;
                    session.TotalBytes = segmentedResult.TotalBytes;
                    session.BytesPerSecond = 0;
                    session.EntityTag = segmentedResult.EntityTag;
                    session.LastModified = segmentedResult.LastModified;
                    session.State = DownloadState.Finalizing;
                }

                WriteFinalizationMarker(session.DestinationPath, segmentedResult.TotalBytes);
                Publish(session, forcePersist: true);
                CompleteFromPartial(session, partialPath, segmentedResult.TotalBytes);
                return;
            }
        }

        using HttpRequestMessage request = new(new HttpMethod(session.Method), session.Source);
        if (session.RequestBody is not null)
        {
            request.Content = new ByteArrayContent(session.RequestBody);
            if (!string.IsNullOrWhiteSpace(session.RequestBodyContentType))
            {
                request.Content.Headers.ContentType = new MediaTypeHeaderValue(session.RequestBodyContentType);
            }
        }

        ApplyRequestMetadata(request, session);
        if (session.Method == "GET" && existingLength > 0)
        {
            request.Headers.Range = new RangeHeaderValue(existingLength, null);
            ApplyIfRangeValidator(request, session);
        }

        DownloadEngineLog.DownloadStarted(_logger, session.Id, session.Source, existingLength);
        using HttpResponseMessage response = await _httpClient
            .SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken)
            .ConfigureAwait(false);

        if (existingLength > 0 && response.StatusCode == HttpStatusCode.RequestedRangeNotSatisfiable)
        {
            long? remoteLength = response.Content.Headers.ContentRange?.Length;
            if (remoteLength == existingLength)
            {
                CompleteFromPartial(session, partialPath, existingLength);
                return;
            }
        }

        response.EnsureSuccessStatusCode();

        bool append = false;
        if (existingLength > 0 && response.StatusCode == HttpStatusCode.PartialContent)
        {
            ValidateContentRange(response, existingLength);
            ValidateResumeValidators(response, session);
            append = true;
        }
        else if (existingLength > 0)
        {
            DownloadEngineLog.RangeIgnored(_logger, session.Id, existingLength, response.StatusCode);
            existingLength = 0;
            lock (session.Sync)
            {
                session.EntityTag = null;
                session.LastModified = null;
            }
        }

        long? totalBytes = response.Content.Headers.ContentRange?.Length;
        if (totalBytes is null && response.Content.Headers.ContentLength is long contentLength)
        {
            totalBytes = existingLength + contentLength;
        }

        EnsureDiskCapacity(session.DestinationPath, existingLength, totalBytes);

        lock (session.Sync)
        {
            session.DownloadedBytes = existingLength;
            session.TotalBytes = totalBytes;
            session.EntityTag = response.Headers.ETag?.ToString() ?? session.EntityTag;
            session.LastModified = response.Content.Headers.LastModified ?? session.LastModified;
            session.State = DownloadState.Downloading;
            session.ErrorMessage = null;
        }

        Publish(session, forcePersist: true);

        await using Stream source = await response.Content
            .ReadAsStreamAsync(cancellationToken)
            .ConfigureAwait(false);

        long downloadedBytes;
        await using (FileStream destination = new(
            partialPath,
            append ? FileMode.Append : FileMode.Create,
            FileAccess.Write,
            FileShare.Read,
            BufferSize,
            FileOptions.Asynchronous | FileOptions.SequentialScan))
        {
            byte[] buffer = new byte[BufferSize];
            Stopwatch speedWatch = Stopwatch.StartNew();
            long speedStartBytes = existingLength;
            DateTimeOffset lastProgress = DateTimeOffset.MinValue;

            while (true)
            {
                int read = await source
                    .ReadAsync(buffer, cancellationToken)
                    .ConfigureAwait(false);
                if (read == 0)
                {
                    break;
                }

                await destination
                    .WriteAsync(buffer.AsMemory(0, read), cancellationToken)
                    .ConfigureAwait(false);

                lock (session.Sync)
                {
                    session.DownloadedBytes += read;
                    double elapsedSeconds = Math.Max(speedWatch.Elapsed.TotalSeconds, 0.001);
                    session.BytesPerSecond = (session.DownloadedBytes - speedStartBytes) / elapsedSeconds;
                }

                await ApplySpeedLimitAsync(
                    session,
                    speedWatch,
                    existingLength,
                    cancellationToken).ConfigureAwait(false);

                DateTimeOffset now = DateTimeOffset.UtcNow;
                if (now - lastProgress >= ProgressInterval)
                {
                    lastProgress = now;
                    Publish(session, forcePersist: false);
                }
            }

            await destination.FlushAsync(cancellationToken).ConfigureAwait(false);
            destination.Flush(flushToDisk: true);

            lock (session.Sync)
            {
                downloadedBytes = session.DownloadedBytes;
            }

            if (totalBytes is long expectedLength && downloadedBytes != expectedLength)
            {
                throw new EndOfStreamException(
                    $"The server ended the response at {downloadedBytes} bytes; {expectedLength} bytes were expected.");
            }
        }

        // Windows does not permit moving the partial file while its stream is open.
        // Keep finalization outside the FileStream scope so all platforms observe
        // the same handle-lifetime semantics.
        WriteFinalizationMarker(session.DestinationPath, downloadedBytes);
        lock (session.Sync)
        {
            session.State = DownloadState.Finalizing;
            session.BytesPerSecond = 0;
        }

        Publish(session, forcePersist: true);
        CompleteFromPartial(session, partialPath, downloadedBytes);
    }

    private static void ApplyIfRangeValidator(HttpRequestMessage request, DownloadSession session)
    {
        string? entityTag;
        DateTimeOffset? lastModified;
        lock (session.Sync)
        {
            entityTag = session.EntityTag;
            lastModified = session.LastModified;
        }

        if (!string.IsNullOrWhiteSpace(entityTag)
            && EntityTagHeaderValue.TryParse(entityTag, out EntityTagHeaderValue? parsedEntityTag)
            && !parsedEntityTag.IsWeak)
        {
            request.Headers.IfRange = new RangeConditionHeaderValue(parsedEntityTag);
            return;
        }

        if (lastModified is DateTimeOffset timestamp)
        {
            request.Headers.IfRange = new RangeConditionHeaderValue(timestamp);
        }
    }

    private static void ValidateContentRange(HttpResponseMessage response, long expectedStart)
    {
        ContentRangeHeaderValue? contentRange = response.Content.Headers.ContentRange;
        if (contentRange?.From != expectedStart)
        {
            throw new DownloadIntegrityException(
                $"The server returned an invalid range start. Expected {expectedStart.ToString(System.Globalization.CultureInfo.InvariantCulture)}; received {contentRange?.From?.ToString(System.Globalization.CultureInfo.InvariantCulture) ?? "none"}.");
        }
    }

    private static void ValidateResumeValidators(HttpResponseMessage response, DownloadSession session)
    {
        string? expectedEntityTag;
        DateTimeOffset? expectedLastModified;
        lock (session.Sync)
        {
            expectedEntityTag = session.EntityTag;
            expectedLastModified = session.LastModified;
        }

        string? responseEntityTag = response.Headers.ETag?.ToString();
        if (!string.IsNullOrWhiteSpace(expectedEntityTag)
            && !string.IsNullOrWhiteSpace(responseEntityTag)
            && !string.Equals(expectedEntityTag, responseEntityTag, StringComparison.Ordinal))
        {
            throw new DownloadIntegrityException("The remote file entity tag changed while resuming.");
        }

        DateTimeOffset? responseLastModified = response.Content.Headers.LastModified;
        if (expectedLastModified is DateTimeOffset expected
            && responseLastModified is DateTimeOffset actual
            && expected != actual)
        {
            throw new DownloadIntegrityException("The remote file modification date changed while resuming.");
        }
    }

    private void EnsureDiskCapacity(string destinationPath, long existingLength, long? totalBytes)
    {
        if (totalBytes is not long total || total <= existingLength)
        {
            return;
        }

        long remainingBytes = total - existingLength;
        long safetyMargin = Math.Min(
            DiskSafetyMarginBytes,
            Math.Max(64L * 1024, remainingBytes / 100));
        long requiredBytes = checked(remainingBytes + safetyMargin);
        long? availableBytes = _diskSpaceProvider.GetAvailableBytes(destinationPath);
        if (availableBytes is long available && available < requiredBytes)
        {
            throw new InsufficientDiskSpaceException(requiredBytes, available, destinationPath);
        }
    }

    private static void WriteFinalizationMarker(string destinationPath, long downloadedBytes)
    {
        string markerPath = GetFinalizationMarkerPath(destinationPath);
        string temporaryPath = $"{markerPath}.tmp";
        byte[] payload = Encoding.UTF8.GetBytes(downloadedBytes.ToString(System.Globalization.CultureInfo.InvariantCulture));
        using (FileStream stream = new(
            temporaryPath,
            FileMode.Create,
            FileAccess.Write,
            FileShare.None,
            4096,
            FileOptions.WriteThrough))
        {
            stream.Write(payload);
            stream.Flush(flushToDisk: true);
        }

        File.Move(temporaryPath, markerPath, overwrite: true);
    }

    private static void RecoverInterruptedFinalization(DownloadSession session)
    {
        string markerPath = GetFinalizationMarkerPath(session.DestinationPath);
        if (!File.Exists(markerPath))
        {
            return;
        }

        string partialPath = GetPartialPath(session.DestinationPath);
        if (File.Exists(session.DestinationPath))
        {
            long completedLength = new FileInfo(session.DestinationPath).Length;
            session.DownloadedBytes = completedLength;
            session.TotalBytes ??= completedLength;
            session.State = DownloadState.Completed;
            session.ErrorMessage = null;
            File.Delete(markerPath);
            return;
        }

        if (File.Exists(partialPath))
        {
            long completedLength = new FileInfo(partialPath).Length;
            File.Move(partialPath, session.DestinationPath, overwrite: true);
            session.DownloadedBytes = completedLength;
            session.TotalBytes ??= completedLength;
            session.State = DownloadState.Completed;
            session.ErrorMessage = null;
            File.Delete(markerPath);
            return;
        }

        session.State = DownloadState.Failed;
        session.ErrorMessage = "Finalization was interrupted and the partial file is missing.";
    }

    private void CompleteFromPartial(DownloadSession session, string partialPath, long downloadedBytes)
    {
        File.Move(partialPath, session.DestinationPath, overwrite: true);
        string markerPath = GetFinalizationMarkerPath(session.DestinationPath);
        if (File.Exists(markerPath))
        {
            File.Delete(markerPath);
        }

        lock (session.Sync)
        {
            session.DownloadedBytes = downloadedBytes;
            session.TotalBytes ??= downloadedBytes;
            session.BytesPerSecond = 0;
            session.State = DownloadState.Completed;
            session.ErrorMessage = null;
        }

        Publish(session, forcePersist: true);
        DownloadEngineLog.DownloadCompleted(_logger, session.Id, session.DestinationPath);
    }

    private void Fail(DownloadSession session, Exception exception)
    {
        lock (session.Sync)
        {
            session.State = DownloadState.Failed;
            session.BytesPerSecond = 0;
            session.ErrorMessage = exception.Message;
        }

        Publish(session, forcePersist: true);
        DownloadEngineLog.DownloadFailed(_logger, session.Id, exception.Message, exception);
    }

    private void Publish(DownloadSession session, bool forcePersist)
    {
        lock (session.Sync)
        {
            session.UpdatedAt = DateTimeOffset.UtcNow;
        }

        _applicationState.UpsertDownload(CreateSnapshot(session));
        _ = PersistSafelyAsync(forcePersist);
    }

    private async Task PersistSafelyAsync(bool force)
    {
        try
        {
            await PersistAsync(force, CancellationToken.None).ConfigureAwait(false);
        }
        catch (IOException exception)
        {
            DownloadEngineLog.HistoryPersistenceFailed(_logger, exception.Message, exception);
        }
        catch (UnauthorizedAccessException exception)
        {
            DownloadEngineLog.HistoryPersistenceFailed(_logger, exception.Message, exception);
        }
    }

    private async Task PersistAsync(bool force, CancellationToken cancellationToken)
    {
        DateTimeOffset now = DateTimeOffset.UtcNow;
        if (!force && now - _lastPersistence < PersistenceInterval)
        {
            return;
        }

        await _persistenceGate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            now = DateTimeOffset.UtcNow;
            if (!force && now - _lastPersistence < PersistenceInterval)
            {
                return;
            }

            PersistedDownload[] downloads = _sessions.Values
                .Select(CreatePersistedDownload)
                .OrderByDescending(static item => item.UpdatedAt)
                .ToArray();

            await _historyStore.SaveAsync(downloads, cancellationToken).ConfigureAwait(false);
            _lastPersistence = now;
        }
        finally
        {
            _persistenceGate.Release();
        }
    }

    private DownloadSession GetSession(string downloadId)
    {
        ThrowIfDisposed();
        ArgumentException.ThrowIfNullOrWhiteSpace(downloadId);

        return _sessions.TryGetValue(downloadId, out DownloadSession? session)
            ? session
            : throw new KeyNotFoundException($"Download '{downloadId}' was not found.");
    }

    private string ResolveDestinationPath(DownloadRequest request, string fileName)
    {
        string candidate = Path.Combine(request.DestinationDirectory, fileName);
        bool sessionCollision = _sessions.Values.Any(session =>
            string.Equals(session.DestinationPath, candidate, StringComparison.OrdinalIgnoreCase));
        bool fileCollision = File.Exists(candidate);

        // A matching .part file is resumable state, not a destination collision.
        // Overwrite handling removes it after this method returns; the normal
        // auto-rename path must preserve the established resume behavior.
        if (!sessionCollision && !fileCollision)
        {
            return candidate;
        }

        if (request.DuplicateBehavior == DuplicateFileBehavior.Skip)
        {
            throw new IOException(sessionCollision
                ? $"A download already uses '{candidate}'."
                : $"The destination file already exists: '{candidate}'.");
        }

        if (request.DuplicateBehavior == DuplicateFileBehavior.Overwrite)
        {
            if (sessionCollision)
            {
                throw new IOException($"A download already uses '{candidate}'.");
            }

            return candidate;
        }

        string directory = Path.GetDirectoryName(candidate)!;
        string extension = Path.GetExtension(candidate);
        string stem = Path.GetFileNameWithoutExtension(candidate);
        for (int index = 1; index < 10_000; index++)
        {
            string renamed = Path.Combine(directory, $"{stem} ({index}){extension}");
            bool isUsed = File.Exists(renamed)
                || File.Exists(GetPartialPath(renamed))
                || Directory.Exists(SegmentedDownloadExecutor.GetSegmentDirectory(renamed))
                || _sessions.Values.Any(session =>
                    string.Equals(session.DestinationPath, renamed, StringComparison.OrdinalIgnoreCase));
            if (!isUsed)
            {
                return renamed;
            }
        }

        throw new IOException("Could not find an available destination filename.");
    }

    private static void ApplyRequestMetadata(HttpRequestMessage request, DownloadSession session)
        => HttpRequestMetadata.Apply(
            request,
            session.Headers,
            session.Username,
            session.Password,
            session.Cookie,
            session.Referer,
            session.UserAgent);

    private async Task ApplySpeedLimitAsync(
        DownloadSession session,
        Stopwatch elapsed,
        long startingBytes,
        CancellationToken cancellationToken)
    {
        long speedLimit = ResolveSpeedLimit(session);
        if (speedLimit <= 0)
        {
            return;
        }

        long downloadedThisRun;
        lock (session.Sync)
        {
            downloadedThisRun = Math.Max(0, session.DownloadedBytes - startingBytes);
        }

        TimeSpan expected = TimeSpan.FromSeconds((double)downloadedThisRun / speedLimit);
        TimeSpan delay = expected - elapsed.Elapsed;
        if (delay > TimeSpan.FromMilliseconds(2))
        {
            await Task.Delay(delay, cancellationToken).ConfigureAwait(false);
        }
    }

    private bool IsQueueActive(string queueId)
        => _activeQueues.ContainsKey(queueId);

    private SemaphoreSlim GetQueueGate(string queueId)
    {
        int concurrency = _settingsService.Current.Queues
            .FirstOrDefault(queue => string.Equals(queue.Id, queueId, StringComparison.Ordinal))
            ?.MaxConcurrentDownloads ?? _settingsService.Current.MaxConcurrentDownloads;
        return _queueGates.GetOrAdd(queueId, _ => new SemaphoreSlim(Math.Max(1, concurrency)));
    }

    private long ResolveSpeedLimit(DownloadSession session)
    {
        long queueLimit = _settingsService.Current.Queues
            .FirstOrDefault(queue => string.Equals(queue.Id, session.QueueId, StringComparison.Ordinal))
            ?.SpeedLimitBytesPerSecond ?? 0;
        long defaultLimit = _settingsService.Current.DefaultSpeedLimitBytesPerSecond;
        long requestLimit = session.SpeedLimitBytesPerSecond ?? 0;
        long[] limits = [requestLimit, queueLimit, defaultLimit];
        return limits.Where(static value => value > 0).DefaultIfEmpty(0).Min();
    }

    private void NormalizeQueueOrder(string queueId)
    {
        DownloadSession[] ordered = _sessions.Values
            .Where(session => string.Equals(session.QueueId, queueId, StringComparison.Ordinal))
            .OrderBy(static session => session.QueueOrder)
            .ThenBy(static session => session.Id, StringComparer.Ordinal)
            .ToArray();
        for (int index = 0; index < ordered.Length; index++)
        {
            lock (ordered[index].Sync)
            {
                ordered[index].QueueOrder = index;
            }
        }
    }

    private QueueRuntimeSnapshot CreateQueueRuntimeSnapshot()
    {
        HashSet<string> activeQueues = new(_activeQueues.Keys, StringComparer.Ordinal);
        Dictionary<string, int> runningCounts = _sessions.Values
            .Where(session => session.State is DownloadState.Connecting or DownloadState.Downloading or DownloadState.Finalizing)
            .GroupBy(static session => session.QueueId, StringComparer.Ordinal)
            .ToDictionary(static group => group.Key, static group => group.Count(), StringComparer.Ordinal);
        return new QueueRuntimeSnapshot(activeQueues, runningCounts, DateTimeOffset.UtcNow);
    }

    private void PublishQueueRuntime()
        => QueueRuntimeChanged?.Invoke(this, CreateQueueRuntimeSnapshot());

    private static void DeleteTransferArtifacts(string destinationPath)
    {
        string partialPath = GetPartialPath(destinationPath);
        if (File.Exists(partialPath))
        {
            File.Delete(partialPath);
        }

        string markerPath = GetFinalizationMarkerPath(destinationPath);
        if (File.Exists(markerPath))
        {
            File.Delete(markerPath);
        }

        string segmentDirectory = SegmentedDownloadExecutor.GetSegmentDirectory(destinationPath);
        if (Directory.Exists(segmentDirectory))
        {
            Directory.Delete(segmentDirectory, recursive: true);
        }
    }

    private static async Task MoveFileAsync(
        string sourcePath,
        string targetPath,
        bool overwrite,
        CancellationToken cancellationToken)
    {
        try
        {
            File.Move(sourcePath, targetPath, overwrite);
            return;
        }
        catch (IOException) when (!File.Exists(targetPath) || overwrite)
        {
            string temporaryPath = $"{targetPath}.xdm-moving";
            try
            {
                await using (FileStream source = new(
                    sourcePath,
                    FileMode.Open,
                    FileAccess.Read,
                    FileShare.Read,
                    BufferSize,
                    FileOptions.Asynchronous | FileOptions.SequentialScan))
                await using (FileStream destination = new(
                    temporaryPath,
                    FileMode.Create,
                    FileAccess.Write,
                    FileShare.None,
                    BufferSize,
                    FileOptions.Asynchronous | FileOptions.WriteThrough))
                {
                    await source.CopyToAsync(destination, cancellationToken).ConfigureAwait(false);
                    await destination.FlushAsync(cancellationToken).ConfigureAwait(false);
                    destination.Flush(flushToDisk: true);
                }

                File.Move(temporaryPath, targetPath, overwrite);
                File.Delete(sourcePath);
            }
            finally
            {
                if (File.Exists(temporaryPath))
                {
                    File.Delete(temporaryPath);
                }
            }
        }
    }

    private static void ValidateHttpUri(Uri uri, string parameterName)
    {
        ArgumentNullException.ThrowIfNull(uri, parameterName);
        if (!uri.IsAbsoluteUri || uri.Scheme is not ("http" or "https"))
        {
            throw new ArgumentException("Only absolute HTTP and HTTPS URLs are supported.", parameterName);
        }
    }

    private static string GetPartialPath(string destinationPath)
        => $"{destinationPath}.part";

    private static string GetFinalizationMarkerPath(string destinationPath)
        => $"{destinationPath}.finalizing";

    private static DownloadSnapshot CreateSnapshot(DownloadSession session)
    {
        lock (session.Sync)
        {
            return new DownloadSnapshot(
                session.Id,
                Path.GetFileName(session.DestinationPath),
                session.Source,
                session.DestinationPath,
                session.DownloadedBytes,
                session.TotalBytes,
                session.BytesPerSecond,
                session.State,
                session.UpdatedAt,
                session.ErrorMessage,
                session.QueueId,
                session.CategoryId,
                session.QueueOrder,
                session.ConnectionCount,
                session.Priority,
                session.SourcePage);
        }
    }

    private static PersistedDownload CreatePersistedDownload(DownloadSession session)
    {
        lock (session.Sync)
        {
            return new PersistedDownload(
                session.Id,
                session.Source,
                session.DestinationPath,
                session.DownloadedBytes,
                session.TotalBytes,
                session.State,
                session.UpdatedAt,
                session.ErrorMessage,
                session.QueueId,
                session.CategoryId,
                session.QueueOrder,
                session.EntityTag,
                session.LastModified,
                session.ConnectionCount,
                session.Method,
                session.Priority,
                session.SourcePage);
        }
    }

    private static DownloadRetryPolicy CreateRetryPolicy(ApplicationSettings settings)
    {
        NetworkSettings network = (settings.Network ?? NetworkSettings.Default).Normalize();
        return new DownloadRetryPolicy(
            network.MaximumRetryAttempts,
            TimeSpan.FromMilliseconds(network.RetryBaseDelayMilliseconds));
    }

    private static SegmentedDownloadOptions CreateSegmentedOptions(ApplicationSettings settings)
    {
        NetworkSettings network = (settings.Network ?? NetworkSettings.Default).Normalize();
        return new SegmentedDownloadOptions(
            network.DefaultConnectionCount,
            network.MaximumConnectionCount,
            network.MinimumSegmentedSizeBytes);
    }

    private void ThrowIfDisposed()
        => ObjectDisposedException.ThrowIf(_disposed, this);

    private sealed class DownloadSession
    {
        public DownloadSession(
            string id,
            Uri source,
            string destinationPath,
            DownloadState state,
            long downloadedBytes,
            long? totalBytes,
            string? errorMessage,
            IReadOnlyDictionary<string, string>? headers,
            string? username,
            string? password,
            string? cookie,
            string? referer,
            string? userAgent,
            long? speedLimitBytesPerSecond = null,
            string queueId = "default",
            string? categoryId = null,
            int queueOrder = 0,
            string? entityTag = null,
            DateTimeOffset? lastModified = null,
            int connectionCount = 4,
            string method = "GET",
            byte[]? requestBody = null,
            string? requestBodyContentType = null,
            DownloadPriority priority = DownloadPriority.Normal,
            Uri? sourcePage = null,
            DateTimeOffset? updatedAt = null)
        {
            Id = id;
            Source = source;
            DestinationPath = destinationPath;
            State = state;
            DownloadedBytes = downloadedBytes;
            TotalBytes = totalBytes;
            ErrorMessage = errorMessage;
            Headers = headers;
            Username = username;
            Password = password;
            Cookie = cookie;
            Referer = referer;
            UserAgent = userAgent;
            SpeedLimitBytesPerSecond = speedLimitBytesPerSecond;
            QueueId = queueId;
            CategoryId = categoryId;
            QueueOrder = queueOrder;
            EntityTag = entityTag;
            LastModified = lastModified;
            ConnectionCount = Math.Clamp(connectionCount, 1, 32);
            Method = method;
            RequestBody = requestBody;
            RequestBodyContentType = requestBodyContentType;
            Priority = priority;
            SourcePage = sourcePage;
            UpdatedAt = updatedAt ?? DateTimeOffset.UtcNow;
        }

        public object Sync { get; } = new();

        public string Id { get; }

        public Uri Source { get; set; }

        public Uri? SourcePage { get; set; }

        public DateTimeOffset UpdatedAt { get; set; }

        public string DestinationPath { get; set; }

        public DownloadState State { get; set; }

        public long DownloadedBytes { get; set; }

        public long? TotalBytes { get; set; }

        public double BytesPerSecond { get; set; }

        public string? ErrorMessage { get; set; }

        public IReadOnlyDictionary<string, string>? Headers { get; }

        public string? Username { get; }

        public string? Password { get; }

        public string? Cookie { get; }

        public string? Referer { get; }

        public string? UserAgent { get; }

        public long? SpeedLimitBytesPerSecond { get; }

        public string QueueId { get; set; }

        public string? CategoryId { get; }

        public int QueueOrder { get; set; }

        public string? EntityTag { get; set; }

        public DateTimeOffset? LastModified { get; set; }

        public int ConnectionCount { get; }

        public string Method { get; }

        public byte[]? RequestBody { get; }

        public string? RequestBodyContentType { get; }

        public DownloadPriority Priority { get; set; }

        public bool PauseRequested { get; set; }

        public bool QueueStopRequested { get; set; }

        public CancellationTokenSource? OperationCancellation { get; set; }

        public Task? ActiveTask { get; set; }
    }
}
