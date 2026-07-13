using System.Collections.Concurrent;
using System.Diagnostics;
using System.Net;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using Microsoft.Extensions.Logging;
using XDM.Core.Downloads;
using XDM.Core.Persistence;
using XDM.Core.Policies;
using XDM.Core.Product;
using XDM.Core.Queues;
using XDM.Core.Settings;
using XDM.Core.State;
using XDM.DownloadEngine.Aria2;
using XDM.DownloadEngine.Backends;
using XDM.DownloadEngine.Logging;
using XDM.DownloadEngine.Policies;
using XDM.DownloadEngine.Queues;

namespace XDM.DownloadEngine;

public sealed class DownloadManager : IDownloadManager, IDisposable
{
    private const int BufferSize = 64 * 1024;
    private const long DiskSafetyMarginBytes = 8L * 1024 * 1024;
    private static readonly TimeSpan ProgressInterval = TimeSpan.FromMilliseconds(150);
    private static readonly TimeSpan PersistenceInterval = TimeSpan.FromSeconds(2);
    private static readonly TimeSpan CheckpointInterval = TimeSpan.FromSeconds(1);
    private const long CheckpointByteInterval = 4L * 1024 * 1024;

    private readonly HttpClient _httpClient;
    private readonly IApplicationState _applicationState;
    private readonly IDownloadHistoryStore _historyStore;
    private readonly ILogger<DownloadManager> _logger;
    private readonly IDiskSpaceProvider _diskSpaceProvider;
    private readonly DownloadRetryPolicy _retryPolicy;
    private readonly SegmentedDownloadExecutor _segmentedExecutor;
    private readonly ResumeCheckpointStore _checkpointStore;
    private readonly IFtpDownloadClient _ftpDownloadClient;
    private readonly ISettingsService _settingsService;
    private readonly ITransferPolicyRuntime _transferPolicyRuntime;
    private readonly IAria2Service? _aria2Service;
    private readonly ConcurrentDictionary<string, byte> _activeQueues = new(StringComparer.Ordinal);
    private readonly ConcurrentDictionary<string, byte> _requestedQueueRoots = new(StringComparer.Ordinal);
    private readonly ConcurrentDictionary<string, byte> _requestedQueues = new(StringComparer.Ordinal);
    private readonly ConcurrentDictionary<string, string> _blockedQueues = new(StringComparer.Ordinal);
    private readonly AdaptiveConcurrencyLimiter _policyConcurrencyLimiter = new();
    private readonly AdaptiveConcurrencyLimiter _hostConcurrencyLimiter = new();
    private readonly AdaptiveConcurrencyLimiter _queueConcurrencyLimiter = new();
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
        IAria2Service aria2Service)
        : this(
            httpClient,
            applicationState,
            historyStore,
            settingsService,
            logger,
            new SystemDiskSpaceProvider(),
            CreateRetryPolicy(settingsService.Current),
            CreateSegmentedOptions(settingsService.Current),
            aria2Service: aria2Service)
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
        SegmentedDownloadOptions? segmentedOptions = null,
        IFtpDownloadClient? ftpDownloadClient = null,
        ResumeCheckpointStore? checkpointStore = null,
        ITransferPolicyRuntime? transferPolicyRuntime = null,
        IAria2Service? aria2Service = null)
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
        _ftpDownloadClient = ftpDownloadClient ?? new FtpDownloadClient();
        _checkpointStore = checkpointStore ?? new ResumeCheckpointStore();
        _transferPolicyRuntime = transferPolicyRuntime ?? UnrestrictedTransferPolicyRuntime.Instance;
        _aria2Service = aria2Service;
        _transferPolicyRuntime.Changed += OnTransferPolicyChanged;
        _settingsService.Changed += OnSettingsChanged;
        if (_aria2Service is not null)
        {
            _aria2Service.Changed += OnAria2SnapshotChanged;
        }
        IReadOnlyList<DownloadQueueDefinition> configuredQueues = settingsService.Current.Queues;
        string defaultQueueId = configuredQueues.Count > 0 ? configuredQueues[0].Id : "default";
        _requestedQueueRoots.TryAdd(defaultQueueId, 0);
        RebuildRequestedQueues();
        ReconcileRequestedQueues();
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
                item.UpdatedAt,
                item.ExpectedChecksumAlgorithm,
                item.ExpectedChecksum,
                item.ActualChecksum,
                item.LastVerifiedAt,
                item.IntegrityStatus,
                item.RecoveryRequired,
                item.RecoveryMessage,
                item.Mirrors,
                item.BackendPreference,
                item.Backend,
                item.BackendTaskId,
                item.BackendDecisionReason,
                item.AllowBackendFallback);

            try
            {
                MigrateLegacyArtifacts(session.DestinationPath);
                ResumeCheckpoint? checkpoint = await _checkpointStore
                    .LoadAsync(session.DestinationPath, cancellationToken)
                    .ConfigureAwait(false);
                ReconcileRecoveredState(session, checkpoint);
                await RecoverInterruptedFinalizationAsync(session, cancellationToken).ConfigureAwait(false);
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
        ReconcileRequestedQueues();
        if (_aria2Service?.Current.Health.IsAvailable == true)
        {
            ApplyAria2Snapshot(_aria2Service.Current);
        }

        foreach (DownloadSession session in _sessions.Values
            .Where(session => session.State == DownloadState.Queued && IsQueueActive(session.QueueId))
            .OrderByDescending(static session => session.Priority)
            .ThenBy(static session => session.QueueOrder))
        {
            Start(session);
        }

        PublishQueueRuntime();
    }

    public async Task<string> AddAsync(DownloadRequest request, CancellationToken cancellationToken = default)
    {
        ThrowIfDisposed();
        ArgumentNullException.ThrowIfNull(request);
        cancellationToken.ThrowIfCancellationRequested();

        if (!ModernFeaturePolicy.IsSupportedDownloadUri(request.Source))
        {
            throw new ArgumentException(
                ModernFeaturePolicy.GetUnsupportedDownloadMessage(request.Source),
                nameof(request));
        }

        string method = string.IsNullOrWhiteSpace(request.Method) ? "GET" : request.Method.Trim().ToUpperInvariant();
        if (method is not ("GET" or "POST"))
        {
            throw new ArgumentException("Only GET and POST download requests are supported.", nameof(request));
        }

        if (request.Source.Scheme is "ftp" or "ftps" && method != "GET")
        {
            throw new ArgumentException("FTP and FTPS downloads support GET semantics only.", nameof(request));
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

        if (request.ExpectedLength is <= 0)
        {
            throw new ArgumentOutOfRangeException(
                nameof(request),
                "Expected length must be greater than zero when provided.");
        }

        string? normalizedExpectedAlgorithm = NormalizeExpectedAlgorithm(
            request.ExpectedChecksumAlgorithm,
            request.ExpectedChecksum);
        string? normalizedExpectedChecksum = NormalizeExpectedChecksum(
            request.ExpectedChecksumAlgorithm,
            request.ExpectedChecksum);
        Uri[] normalizedRequestMirrors = NormalizeMirrors(request.Source, request.Mirrors);
        bool allowBackendFallback = request.AllowBackendFallback
            && (_settingsService.Current.Aria2 ?? Aria2IntegrationSettings.Default)
                .Normalize()
                .AllowNativeFallback;

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
        MigrateLegacyArtifacts(destinationPath);
        if (request.DuplicateBehavior == DuplicateFileBehavior.Overwrite)
        {
            string overwritePartialPath = GetPartialPath(destinationPath);
            if (File.Exists(overwritePartialPath))
            {
                File.Delete(overwritePartialPath);
            }

            string legacyPartialPath = TransferArtifactPaths.GetLegacyPartialPath(destinationPath);
            if (File.Exists(legacyPartialPath))
            {
                File.Delete(legacyPartialPath);
            }

            _checkpointStore.Delete(destinationPath);
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

        ResumeCheckpoint? recoveryCheckpoint = null;
        bool recoverLegacySegments = false;
        if (request.DuplicateBehavior != DuplicateFileBehavior.Overwrite
            && HasTransferArtifacts(destinationPath))
        {
            recoveryCheckpoint = await _checkpointStore
                .LoadAsync(destinationPath, cancellationToken)
                .ConfigureAwait(false);
            if (!IsCheckpointCompatibleWithRequest(
                    recoveryCheckpoint,
                    request.Source,
                    normalizedRequestMirrors,
                    destinationPath,
                    request.ExpectedLength,
                    normalizedExpectedAlgorithm,
                    normalizedExpectedChecksum))
            {
                recoverLegacySegments = CanAdoptLegacySegmentArtifacts(destinationPath);
                if (!recoverLegacySegments)
                {
                    PreserveOrphanedTransferArtifacts(destinationPath);
                }
                recoveryCheckpoint = null;
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
            recoveryCheckpoint?.Source ?? request.Source,
            destinationPath,
            DownloadState.Queued,
            0,
            request.ExpectedLength ?? recoveryCheckpoint?.TotalBytes,
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
            recoveryCheckpoint?.EntityTag,
            recoveryCheckpoint?.LastModified,
            method == "GET" ? Math.Clamp(request.ConnectionCount, 1, 32) : 1,
            method,
            request.RequestBody?.ToArray(),
            request.RequestBodyContentType,
            request.Priority,
            request.SourcePage,
            DateTimeOffset.UtcNow,
            normalizedExpectedAlgorithm ?? recoveryCheckpoint?.ExpectedChecksumAlgorithm,
            normalizedExpectedChecksum ?? recoveryCheckpoint?.ExpectedChecksum,
            null,
            null,
            DownloadIntegrityStatus.Unknown,
            false,
            null,
            new[] { request.Source }
                .Concat(normalizedRequestMirrors)
                .Concat(recoveryCheckpoint?.Mirrors ?? Array.Empty<Uri>())
                .ToArray(),
            request.BackendPreference,
            DownloadBackendKind.Native,
            null,
            null,
            allowBackendFallback);

        if (!_sessions.TryAdd(session.Id, session))
        {
            throw new InvalidOperationException("Could not register the new download.");
        }

        if (recoveryCheckpoint is not null || recoverLegacySegments)
        {
            ReconcileRecoveredState(session, recoveryCheckpoint, allowDownloadIdMismatch: true);
            if (recoverLegacySegments)
            {
                lock (session.Sync)
                {
                    session.RecoveryMessage =
                        "Recovered legacy segmented transfer state. The server will be probed and each range validated before reuse.";
                }
            }
            await RecoverInterruptedFinalizationAsync(session, cancellationToken).ConfigureAwait(false);
        }

        Publish(session, forcePersist: true);
        if (session.State != DownloadState.Completed && !session.RecoveryRequired && IsQueueActive(session.QueueId))
        {
            Start(session);
        }

        PublishQueueRuntime();
        return session.Id;
    }

    public async Task<DownloadVerificationResult> VerifyAsync(
        string downloadId,
        CancellationToken cancellationToken = default)
    {
        DownloadSession session = GetSession(downloadId);
        string filePath;
        string algorithm;
        string? expectedChecksum;
        lock (session.Sync)
        {
            if (session.State != DownloadState.Completed)
            {
                throw new InvalidOperationException("Only completed downloads can be verified.");
            }

            filePath = session.DestinationPath;
            algorithm = session.ExpectedChecksumAlgorithm ?? DownloadChecksumService.Sha256;
            expectedChecksum = session.ExpectedChecksum;
            session.IntegrityStatus = DownloadIntegrityStatus.Verifying;
            session.RecoveryRequired = false;
            session.RecoveryMessage = null;
        }

        if (!File.Exists(filePath))
        {
            lock (session.Sync)
            {
                session.IntegrityStatus = DownloadIntegrityStatus.RecoveryRequired;
                session.RecoveryRequired = true;
                session.RecoveryMessage = "The completed file is missing and must be downloaded again.";
            }
            Publish(session, forcePersist: true);
            throw new FileNotFoundException("The completed download file no longer exists.", filePath);
        }

        Publish(session, forcePersist: false);
        string actualChecksum = await DownloadChecksumService
            .ComputeAsync(filePath, algorithm, cancellationToken)
            .ConfigureAwait(false);
        bool isMatch = expectedChecksum is null
            || string.Equals(actualChecksum, expectedChecksum, StringComparison.OrdinalIgnoreCase);
        DateTimeOffset verifiedAt = DateTimeOffset.UtcNow;
        lock (session.Sync)
        {
            session.ActualChecksum = actualChecksum;
            session.LastVerifiedAt = verifiedAt;
            session.IntegrityStatus = isMatch
                ? DownloadIntegrityStatus.Verified
                : DownloadIntegrityStatus.Mismatch;
            session.RecoveryRequired = !isMatch;
            session.RecoveryMessage = isMatch
                ? null
                : "The downloaded file does not match its expected checksum. Use Repair to preserve it and download a clean copy.";
            session.ErrorMessage = isMatch ? null : session.RecoveryMessage;
        }

        Publish(session, forcePersist: true);
        string message = expectedChecksum is null
            ? $"Recorded {algorithm} checksum {actualChecksum}."
            : isMatch
                ? $"{algorithm} verification succeeded."
                : $"{algorithm} verification failed.";
        return new DownloadVerificationResult(
            session.Id,
            filePath,
            algorithm,
            actualChecksum,
            expectedChecksum,
            isMatch,
            new FileInfo(filePath).Length,
            message);
    }

    public async Task<DownloadRepairResult> RepairAsync(
        string downloadId,
        CancellationToken cancellationToken = default)
    {
        DownloadSession session = GetSession(downloadId);
        cancellationToken.ThrowIfCancellationRequested();
        lock (session.Sync)
        {
            if (session.State is DownloadState.Connecting or DownloadState.Downloading or DownloadState.Finalizing)
            {
                throw new InvalidOperationException("Pause the download before repairing it.");
            }
            session.IntegrityStatus = DownloadIntegrityStatus.Repairing;
        }
        Publish(session, forcePersist: false);

        string? preservedPath = null;
        string destinationPath;
        string? aria2Gid;
        lock (session.Sync)
        {
            destinationPath = session.DestinationPath;
            aria2Gid = session.Backend == DownloadBackendKind.Aria2
                ? session.BackendTaskId
                : null;
        }

        if (aria2Gid is not null && _aria2Service is not null)
        {
            try
            {
                await _aria2Service.RemoveAsync(aria2Gid, cancellationToken).ConfigureAwait(false);
            }
            catch (Aria2RpcException exception) when (exception.Code is 1 or 2)
            {
            }
        }

        if (File.Exists(destinationPath))
        {
            preservedPath = ResolveUniqueArtifactPath(
                TransferArtifactPaths.GetCorruptBackupPath(destinationPath, DateTimeOffset.UtcNow));
            await MoveFileAsync(destinationPath, preservedPath, overwrite: false, cancellationToken)
                .ConfigureAwait(false);
        }

        DeleteTransferArtifacts(destinationPath);
        lock (session.Sync)
        {
            session.DownloadedBytes = 0;
            session.BytesPerSecond = 0;
            session.EntityTag = null;
            session.LastModified = null;
            session.ActualChecksum = null;
            session.LastVerifiedAt = null;
            session.IntegrityStatus = DownloadIntegrityStatus.Checkpointed;
            session.RecoveryRequired = false;
            session.RecoveryMessage = null;
            session.ErrorMessage = null;
            session.State = DownloadState.Paused;
            session.BackendTaskId = null;
            session.Source = session.Mirrors[0];
            session.MirrorIndex = 1;
        }

        Publish(session, forcePersist: true);
        bool restarted = IsQueueActive(session.QueueId);
        Start(session);
        return new DownloadRepairResult(
            session.Id,
            restarted,
            preservedPath,
            preservedPath is null
                ? restarted
                    ? "The transfer state was reset and the download was restarted."
                    : "The transfer state was reset and queued for the next queue start."
                : restarted
                    ? $"The suspect file was preserved as '{preservedPath}' and a clean download was started."
                    : $"The suspect file was preserved as '{preservedPath}' and a clean download was queued.");
    }

    public async Task<IReadOnlyList<string>> AddMetalinkAsync(
        Stream stream,
        string destinationDirectory,
        string? queueId = null,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(stream);
        ArgumentException.ThrowIfNullOrWhiteSpace(destinationDirectory);
        IReadOnlyList<MetalinkFileEntry> entries = MetalinkDocumentParser.Parse(stream);
        if (entries.Count == 0)
        {
            throw new InvalidDataException("The Metalink document contains no supported download entries.");
        }

        List<string> ids = new(entries.Count);
        foreach (MetalinkFileEntry entry in entries)
        {
            cancellationToken.ThrowIfCancellationRequested();
            Uri primary = entry.Sources[0];
            string id = await AddAsync(
                new DownloadRequest(
                    primary,
                    destinationDirectory,
                    entry.FileName,
                    QueueId: queueId,
                    Mirrors: entry.Sources.Skip(1).ToArray(),
                    ExpectedChecksumAlgorithm: entry.ChecksumAlgorithm,
                    ExpectedChecksum: entry.Checksum,
                    ExpectedLength: entry.Size),
                cancellationToken).ConfigureAwait(false);
            ids.Add(id);
        }
        return ids;
    }

    public async Task PauseAsync(string downloadId, CancellationToken cancellationToken = default)
    {
        DownloadSession session = GetSession(downloadId);
        cancellationToken.ThrowIfCancellationRequested();

        string? aria2Gid;
        bool aria2Owned;
        lock (session.Sync)
        {
            aria2Owned = session.Backend == DownloadBackendKind.Aria2;
            aria2Gid = session.BackendTaskId;
            if (session.State is DownloadState.Connecting or DownloadState.Downloading)
            {
                session.PauseRequested = true;
                if (!aria2Owned || aria2Gid is null)
                {
                    session.OperationCancellation?.Cancel();
                }
            }
            else if (session.State == DownloadState.Queued)
            {
                session.State = DownloadState.Paused;
                Publish(session, forcePersist: true);
            }
        }

        if (aria2Owned && aria2Gid is not null && _aria2Service is not null)
        {
            await _aria2Service.PauseAsync(aria2Gid, cancellationToken).ConfigureAwait(false);
        }
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

    public async Task CancelAsync(string downloadId, CancellationToken cancellationToken = default)
    {
        DownloadSession session = GetSession(downloadId);
        cancellationToken.ThrowIfCancellationRequested();

        string? aria2Gid;
        bool aria2Owned;
        lock (session.Sync)
        {
            session.PauseRequested = false;
            aria2Owned = session.Backend == DownloadBackendKind.Aria2;
            aria2Gid = session.BackendTaskId;
            if (!aria2Owned || aria2Gid is null)
            {
                session.OperationCancellation?.Cancel();
            }

            if (session.State is DownloadState.Queued or DownloadState.Paused or DownloadState.Failed)
            {
                session.State = DownloadState.Cancelled;
                Publish(session, forcePersist: true);
            }
        }

        if (aria2Owned && aria2Gid is not null && _aria2Service is not null)
        {
            await _aria2Service.RemoveAsync(aria2Gid, cancellationToken).ConfigureAwait(false);
            lock (session.Sync)
            {
                if (string.Equals(session.BackendTaskId, aria2Gid, StringComparison.Ordinal))
                {
                    session.BackendTaskId = null;
                }
            }
            Publish(session, forcePersist: true);
        }
    }

    public async Task RetryAsync(string downloadId, CancellationToken cancellationToken = default)
    {
        DownloadSession session = GetSession(downloadId);
        string? aria2Gid;
        lock (session.Sync)
        {
            aria2Gid = session.Backend == DownloadBackendKind.Aria2
                ? session.BackendTaskId
                : null;
        }

        if (aria2Gid is not null && _aria2Service is not null)
        {
            try
            {
                await _aria2Service.RemoveAsync(aria2Gid, cancellationToken).ConfigureAwait(false);
            }
            catch (Aria2RpcException exception) when (exception.Code is 1 or 2)
            {
                // A terminal task may already have been purged by aria2.
            }

            lock (session.Sync)
            {
                if (string.Equals(session.BackendTaskId, aria2Gid, StringComparison.Ordinal))
                {
                    session.BackendTaskId = null;
                }
            }
        }

        await ResumeAsync(downloadId, cancellationToken).ConfigureAwait(false);
    }

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

        string? aria2Gid;
        lock (session.Sync)
        {
            session.PauseRequested = false;
            aria2Gid = session.Backend == DownloadBackendKind.Aria2
                ? session.BackendTaskId
                : null;
            session.OperationCancellation?.Cancel();
        }

        if (aria2Gid is not null && _aria2Service is not null)
        {
            try
            {
                await _aria2Service.RemoveAsync(aria2Gid, cancellationToken).ConfigureAwait(false);
            }
            catch (Aria2RpcException exception) when (exception.Code is 1 or 2)
            {
                // The task is already terminal or absent; local deletion can continue.
            }
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
                SourcePage: session.SourcePage,
                Mirrors: session.Mirrors,
                ExpectedChecksumAlgorithm: session.ExpectedChecksumAlgorithm,
                ExpectedChecksum: session.ExpectedChecksum,
                ExpectedLength: session.TotalBytes,
                BackendPreference: session.BackendPreference,
                AllowBackendFallback: session.AllowBackendFallback);
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
        ValidateDownloadUri(source, nameof(source));
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
            session.Mirrors = new[] { source }
                .Concat(session.Mirrors)
                .Distinct()
                .ToArray();
            session.MirrorIndex = 1;
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

        _requestedQueueRoots.TryAdd(queueId, 0);
        RebuildRequestedQueues();
        ReconcileRequestedQueues();
        PublishQueueRuntime(reconcile: false);
        return Task.CompletedTask;
    }

    public Task StopQueueAsync(string queueId, CancellationToken cancellationToken = default)
    {
        ThrowIfDisposed();
        ArgumentException.ThrowIfNullOrWhiteSpace(queueId);
        cancellationToken.ThrowIfCancellationRequested();

        RemoveQueueRequestTree(queueId);
        RebuildRequestedQueues();
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
        _transferPolicyRuntime.Changed -= OnTransferPolicyChanged;
        _settingsService.Changed -= OnSettingsChanged;
        if (_aria2Service is not null)
        {
            _aria2Service.Changed -= OnAria2SnapshotChanged;
        }
        foreach (DownloadSession session in _sessions.Values)
        {
            lock (session.Sync)
            {
                session.OperationCancellation?.Cancel();
                session.OperationCancellation?.Dispose();
                session.OperationCancellation = null;
            }
        }

        _persistenceGate.Dispose();
    }

    private void Start(DownloadSession session)
    {
        TransferPolicySnapshot policy = _transferPolicyRuntime.Current;
        if (!IsQueueActive(session.QueueId) || policy.IsPaused)
        {
            lock (session.Sync)
            {
                session.State = DownloadState.Queued;
                session.ErrorMessage = policy.IsPaused ? policy.StatusMessage : null;
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
                () => RunBackendSafelyAsync(session, operationCancellation.Token),
                CancellationToken.None);
        }
    }

    private async Task RunBackendSafelyAsync(
        DownloadSession session,
        CancellationToken cancellationToken)
    {
        try
        {
            await RunSelectedBackendAsync(session, cancellationToken).ConfigureAwait(false);
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
        catch (Exception exception) when (exception is HttpRequestException
            or IOException
            or UnauthorizedAccessException
            or InvalidOperationException
            or Aria2RpcException)
        {
            Fail(session, exception);
        }
    }

    private async Task RunSelectedBackendAsync(
        DownloadSession session,
        CancellationToken cancellationToken)
    {
        DownloadRequest request = CreateBackendRequest(session);
        Aria2IntegrationSettings settings = (_settingsService.Current.Aria2 ?? Aria2IntegrationSettings.Default)
            .Normalize();
        Aria2ServiceSnapshot snapshot = _aria2Service?.Current ?? Aria2ServiceSnapshot.Disabled;
        string? ownedGid;
        lock (session.Sync)
        {
            ownedGid = session.BackendTaskId;
        }

        DownloadBackendDecision decision = ownedGid is not null
            ? snapshot.Health.IsAvailable && settings.Enabled
                ? new DownloadBackendDecision(
                    DownloadBackendKind.Aria2,
                    $"Resuming the XDM-owned aria2 task {ownedGid}.")
                : new DownloadBackendDecision(
                    DownloadBackendKind.Aria2,
                    $"The XDM-owned aria2 task {ownedGid} cannot be resumed while aria2 is unavailable: {snapshot.Health.Message}",
                    CanStart: false)
            : DownloadBackendAdvisor.Decide(request, settings, snapshot);
        lock (session.Sync)
        {
            session.Backend = decision.Backend;
            session.BackendDecisionReason = decision.Reason;
            if (!decision.CanStart && ownedGid is not null)
            {
                session.RecoveryRequired = true;
                session.RecoveryMessage = decision.Reason;
            }
        }
        Publish(session, forcePersist: true);

        if (!decision.CanStart)
        {
            throw new InvalidOperationException(decision.Reason);
        }

        if (decision.Backend != DownloadBackendKind.Aria2)
        {
            await RunDownloadAsync(session, cancellationToken).ConfigureAwait(false);
            return;
        }

        if (_aria2Service is null || !settings.Enabled || !_aria2Service.Current.Health.IsAvailable)
        {
            if (!IsNativeFallbackAllowed(session))
            {
                throw new InvalidOperationException(
                    $"aria2 was requested but is not available: {_aria2Service?.Current.Health.Message ?? "the aria2 service is not configured."}");
            }

            SetNativeFallback(session, "aria2 was unavailable before the transfer started.");
            await RunDownloadAsync(session, cancellationToken).ConfigureAwait(false);
            return;
        }

        Aria2TaskSnapshot? collision = Aria2DestinationOwnership.FindCollision(
            session.DestinationPath,
            _aria2Service.Current.Tasks,
            session.BackendTaskId);
        if (collision is not null && !string.Equals(collision.Gid, session.BackendTaskId, StringComparison.Ordinal))
        {
            throw new IOException(
                $"aria2 task {collision.Gid} already owns the destination '{session.DestinationPath}'. "
                + "Choose another destination or remove the conflicting aria2 task before retrying.");
        }

        try
        {
            await RunAria2DownloadAsync(session, cancellationToken).ConfigureAwait(false);
        }
        catch (Exception exception) when (CanFallbackFromAria2(session, exception, cancellationToken))
        {
            SetNativeFallback(session, $"aria2 failed before ownership was established: {exception.Message}");
            await RunDownloadAsync(session, cancellationToken).ConfigureAwait(false);
        }
    }

    private async Task RunAria2DownloadAsync(
        DownloadSession session,
        CancellationToken cancellationToken)
    {
        IAria2Service service = _aria2Service
            ?? throw new InvalidOperationException("aria2 is not configured.");
        IDisposable? queueLease = null;
        IDisposable? policyLease = null;
        IDisposable? hostLease = null;
        try
        {
            queueLease = await _queueConcurrencyLimiter
                .AcquireAsync(session.QueueId, () => ResolveQueueConcurrency(session.QueueId), cancellationToken)
                .ConfigureAwait(false);
            policyLease = await _policyConcurrencyLimiter
                .AcquireAsync("global", ResolveConcurrentDownloadLimit, cancellationToken)
                .ConfigureAwait(false);
            hostLease = await _hostConcurrencyLimiter
                .AcquireAsync(
                    session.Source.IdnHost,
                    () => _transferPolicyRuntime.Current.EffectiveProfile.MaxConcurrentPerHost,
                    cancellationToken)
                .ConfigureAwait(false);

            TaskCompletionSource<Aria2TaskStatus> signal = new(
                TaskCreationOptions.RunContinuationsAsynchronously);
            string? existingGid;
            lock (session.Sync)
            {
                session.Aria2TerminalSignal = signal;
                session.State = DownloadState.Connecting;
                session.ErrorMessage = null;
                existingGid = session.BackendTaskId;
            }
            Publish(session, forcePersist: true);

            Aria2TaskSnapshot? existingTask = existingGid is null
                ? null
                : service.Current.Tasks.FirstOrDefault(task =>
                    string.Equals(task.Gid, existingGid, StringComparison.Ordinal));
            if (existingGid is not null && existingTask is null)
            {
                await service.RefreshAsync(cancellationToken).ConfigureAwait(false);
                if (!service.Current.Health.IsAvailable)
                {
                    throw new InvalidOperationException(
                        $"Could not confirm ownership of aria2 task {existingGid}: {service.Current.Health.Message}");
                }

                existingTask = service.Current.Tasks.FirstOrDefault(task =>
                    string.Equals(task.Gid, existingGid, StringComparison.Ordinal));
                if (existingTask is null)
                {
                    lock (session.Sync)
                    {
                        session.BackendTaskId = null;
                        session.BackendDecisionReason =
                            $"The previous aria2 task {existingGid} no longer exists; a replacement task will be created.";
                    }
                }
            }

            if (existingTask is null)
            {
                Aria2TaskSnapshot? destinationCollision = Aria2DestinationOwnership.FindCollision(
                    session.DestinationPath,
                    service.Current.Tasks);
                if (destinationCollision is not null)
                {
                    throw new IOException(
                        $"aria2 task {destinationCollision.Gid} already owns the destination '{session.DestinationPath}'.");
                }

                IReadOnlyDictionary<string, string> headers = BuildAria2Headers(session);
                string gid;
                try
                {
                    gid = await service.AddAsync(
                        new Aria2AddRequest(
                            session.Source,
                            Path.GetDirectoryName(session.DestinationPath)
                                ?? _settingsService.Current.DefaultDownloadDirectory,
                            Path.GetFileName(session.DestinationPath),
                            headers,
                            session.Username,
                            session.Password,
                            session.SpeedLimitBytesPerSecond)
                        {
                            Mirrors = session.Mirrors.Skip(1).ToArray(),
                            ExpectedChecksumAlgorithm = session.ExpectedChecksumAlgorithm,
                            ExpectedChecksum = session.ExpectedChecksum
                        },
                        cancellationToken).ConfigureAwait(false);
                }
                catch (Exception exception) when (exception is HttpRequestException or Aria2RpcException)
                {
                    Aria2TaskSnapshot? recovered = await TryRecoverUncertainAria2AddAsync(
                        session,
                        cancellationToken).ConfigureAwait(false);
                    if (recovered is null)
                    {
                        throw;
                    }

                    gid = recovered.Gid;
                    existingTask = recovered;
                    lock (session.Sync)
                    {
                        session.BackendDecisionReason =
                            "Adopted the aria2 task discovered after an uncertain add response.";
                    }
                }

                lock (session.Sync)
                {
                    session.BackendTaskId = gid;
                }
            }
            else if (existingTask.Status == Aria2TaskStatus.Paused)
            {
                await service.ResumeAsync(existingTask.Gid, cancellationToken).ConfigureAwait(false);
            }

            ApplyAria2Snapshot(service.Current);
            Aria2TaskStatus terminalStatus = await signal.Task.WaitAsync(cancellationToken).ConfigureAwait(false);
            if (terminalStatus == Aria2TaskStatus.Complete)
            {
                await CompleteAria2DownloadAsync(session, cancellationToken).ConfigureAwait(false);
                return;
            }
            if (terminalStatus == Aria2TaskStatus.Paused)
            {
                lock (session.Sync)
                {
                    session.State = DownloadState.Paused;
                    session.BytesPerSecond = 0;
                }
                Publish(session, forcePersist: true);
                return;
            }
            if (terminalStatus == Aria2TaskStatus.Removed)
            {
                lock (session.Sync)
                {
                    session.State = DownloadState.Cancelled;
                    session.BytesPerSecond = 0;
                }
                Publish(session, forcePersist: true);
                return;
            }

            string message;
            lock (session.Sync)
            {
                message = session.ErrorMessage ?? "aria2 reported a transfer failure.";
            }
            throw new InvalidOperationException(message);
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            string? gid;
            bool pause;
            bool queueStop;
            lock (session.Sync)
            {
                gid = session.BackendTaskId;
                pause = session.PauseRequested;
                queueStop = session.QueueStopRequested;
            }

            if (gid is not null)
            {
                try
                {
                    if (pause || queueStop)
                    {
                        await service.PauseAsync(gid, CancellationToken.None).ConfigureAwait(false);
                    }
                    else
                    {
                        await service.RemoveAsync(gid, CancellationToken.None).ConfigureAwait(false);
                    }
                }
                catch (Exception exception) when (exception is HttpRequestException or Aria2RpcException or InvalidOperationException)
                {
                    DownloadEngineLog.DownloadFailed(_logger, session.Id, exception.Message, exception);
                }
            }

            lock (session.Sync)
            {
                session.State = queueStop
                    ? DownloadState.Queued
                    : pause
                        ? DownloadState.Paused
                        : DownloadState.Cancelled;
                session.QueueStopRequested = false;
                session.BytesPerSecond = 0;
            }
            Publish(session, forcePersist: true);
        }
        finally
        {
            lock (session.Sync)
            {
                session.Aria2TerminalSignal = null;
            }
            hostLease?.Dispose();
            policyLease?.Dispose();
            queueLease?.Dispose();
            PublishQueueRuntime();
        }
    }

    private async Task CompleteAria2DownloadAsync(
        DownloadSession session,
        CancellationToken cancellationToken)
    {
        string path;
        long? expectedLength;
        string? expectedAlgorithm;
        string? expectedChecksum;
        lock (session.Sync)
        {
            path = session.DestinationPath;
            expectedLength = session.TotalBytes;
            expectedAlgorithm = session.ExpectedChecksumAlgorithm;
            expectedChecksum = session.ExpectedChecksum;
            session.State = DownloadState.Finalizing;
            session.BytesPerSecond = 0;
        }
        Publish(session, forcePersist: true);

        if (!File.Exists(path))
        {
            throw new FileNotFoundException("aria2 completed the task but the destination file is missing.", path);
        }

        long actualLength = new FileInfo(path).Length;
        if (expectedLength is long length && length != actualLength)
        {
            throw new DownloadIntegrityException(
                $"aria2 completed a file with an unexpected length. Expected {length}; received {actualLength}.");
        }

        string? actualChecksum = null;
        if (expectedAlgorithm is not null && expectedChecksum is not null)
        {
            actualChecksum = await DownloadChecksumService
                .ComputeAsync(path, expectedAlgorithm, cancellationToken)
                .ConfigureAwait(false);
            if (!string.Equals(actualChecksum, expectedChecksum, StringComparison.OrdinalIgnoreCase))
            {
                throw new DownloadIntegrityException(
                    $"aria2 completed the transfer, but {expectedAlgorithm} verification failed.");
            }
        }

        lock (session.Sync)
        {
            session.DownloadedBytes = actualLength;
            session.TotalBytes = actualLength;
            session.ActualChecksum = actualChecksum;
            session.LastVerifiedAt = actualChecksum is null ? null : DateTimeOffset.UtcNow;
            session.IntegrityStatus = actualChecksum is null
                ? DownloadIntegrityStatus.Unknown
                : DownloadIntegrityStatus.Verified;
            session.State = DownloadState.Completed;
            session.ErrorMessage = null;
            session.RecoveryRequired = false;
            session.RecoveryMessage = null;
        }
        Publish(session, forcePersist: true);
        DownloadEngineLog.DownloadCompleted(_logger, session.Id, session.DestinationPath);
    }

    private void OnAria2SnapshotChanged(object? sender, Aria2ServiceSnapshot snapshot)
        => ApplyAria2Snapshot(snapshot);

    private void ApplyAria2Snapshot(Aria2ServiceSnapshot snapshot)
    {
        HashSet<string> ownedGids = _sessions.Values
            .Select(static session => session.BackendTaskId)
            .Where(static gid => !string.IsNullOrWhiteSpace(gid))
            .Cast<string>()
            .ToHashSet(StringComparer.Ordinal);
        bool adoptExisting = (_settingsService.Current.Aria2 ?? Aria2IntegrationSettings.Default)
            .Normalize()
            .AdoptExistingTasks;

        foreach (DownloadSession session in _sessions.Values.Where(static item =>
            item.Backend == DownloadBackendKind.Aria2 && item.State != DownloadState.Completed))
        {
            Aria2TaskSnapshot? task;
            lock (session.Sync)
            {
                task = session.BackendTaskId is null
                    ? null
                    : snapshot.Tasks.FirstOrDefault(candidate =>
                        string.Equals(candidate.Gid, session.BackendTaskId, StringComparison.Ordinal));
            }

            if (task is null && adoptExisting)
            {
                task = snapshot.Tasks.FirstOrDefault(candidate =>
                    !ownedGids.Contains(candidate.Gid)
                    && !string.IsNullOrWhiteSpace(candidate.DestinationPath)
                    && PathsEqual(candidate.DestinationPath, session.DestinationPath));
                if (task is not null)
                {
                    lock (session.Sync)
                    {
                        session.BackendTaskId = task.Gid;
                        session.BackendDecisionReason = "Adopted the matching aria2 task after restart.";
                    }
                    ownedGids.Add(task.Gid);
                }
            }

            if (task is null)
            {
                continue;
            }

            bool publish;
            bool startMonitor = false;
            bool finalizeAdopted = false;
            lock (session.Sync)
            {
                if (!string.IsNullOrWhiteSpace(task.DestinationPath)
                    && !PathsEqual(task.DestinationPath, session.DestinationPath))
                {
                    session.State = DownloadState.Failed;
                    session.ErrorMessage = "The aria2 task destination no longer matches the XDM-owned destination.";
                    session.RecoveryRequired = true;
                    session.RecoveryMessage = session.ErrorMessage;
                    session.Aria2TerminalSignal?.TrySetResult(Aria2TaskStatus.Error);
                    publish = true;
                }
                else
                {
                    if (session.TotalBytes is long expectedLength
                        && task.TotalBytes > 0
                        && task.TotalBytes != expectedLength)
                    {
                        session.State = DownloadState.Failed;
                        session.BytesPerSecond = 0;
                        session.ErrorMessage =
                            $"aria2 reported a total length of {task.TotalBytes}, but XDM expected {expectedLength}.";
                        session.RecoveryRequired = true;
                        session.RecoveryMessage = session.ErrorMessage;
                        session.Aria2TerminalSignal?.TrySetResult(Aria2TaskStatus.Error);
                        publish = true;
                    }
                    else
                    {
                        session.DownloadedBytes = task.CompletedBytes;
                        session.TotalBytes = task.TotalBytes > 0 ? task.TotalBytes : session.TotalBytes;
                        session.BytesPerSecond = task.DownloadSpeedBytesPerSecond;
                        session.ErrorMessage = task.ErrorMessage;
                        session.State = task.Status switch
                        {
                            Aria2TaskStatus.Active => DownloadState.Downloading,
                            Aria2TaskStatus.Waiting => DownloadState.Connecting,
                            Aria2TaskStatus.Paused => DownloadState.Paused,
                            Aria2TaskStatus.Complete => DownloadState.Finalizing,
                            Aria2TaskStatus.Error => DownloadState.Failed,
                            Aria2TaskStatus.Removed => DownloadState.Cancelled,
                            _ => session.State
                        };
                        if (task.Status is Aria2TaskStatus.Paused
                            or Aria2TaskStatus.Complete
                            or Aria2TaskStatus.Error
                            or Aria2TaskStatus.Removed)
                        {
                            session.Aria2TerminalSignal?.TrySetResult(task.Status);
                        }

                        startMonitor = (task.Status is Aria2TaskStatus.Active or Aria2TaskStatus.Waiting)
                            && session.ActiveTask is not { IsCompleted: false }
                            && IsQueueActive(session.QueueId);
                        finalizeAdopted = task.Status == Aria2TaskStatus.Complete
                            && session.Aria2TerminalSignal is null
                            && !session.Aria2FinalizationScheduled;
                        if (finalizeAdopted)
                        {
                            session.Aria2FinalizationScheduled = true;
                        }
                        publish = true;
                    }
                }
            }

            if (publish)
            {
                Publish(session, forcePersist: task.Status is not Aria2TaskStatus.Active);
            }
            if (startMonitor)
            {
                Start(session);
            }
            if (finalizeAdopted)
            {
                _ = FinalizeAdoptedAria2TaskAsync(session);
            }
        }
    }

    private async Task FinalizeAdoptedAria2TaskAsync(DownloadSession session)
    {
        try
        {
            await CompleteAria2DownloadAsync(session, CancellationToken.None).ConfigureAwait(false);
        }
        catch (Exception exception) when (exception is IOException
            or UnauthorizedAccessException
            or InvalidOperationException)
        {
            Fail(session, exception);
        }
        finally
        {
            lock (session.Sync)
            {
                session.Aria2FinalizationScheduled = false;
            }
        }
    }

    private async Task<Aria2TaskSnapshot?> TryRecoverUncertainAria2AddAsync(
        DownloadSession session,
        CancellationToken cancellationToken)
    {
        if (_aria2Service is null)
        {
            return null;
        }

        try
        {
            await _aria2Service.RefreshAsync(cancellationToken).ConfigureAwait(false);
        }
        catch (Exception exception) when (exception is HttpRequestException or Aria2RpcException or InvalidOperationException)
        {
            return null;
        }

        if (!_aria2Service.Current.Health.IsAvailable)
        {
            return null;
        }

        return Aria2DestinationOwnership.FindCollision(
            session.DestinationPath,
            _aria2Service.Current.Tasks);
    }

    private static IReadOnlyDictionary<string, string> BuildAria2Headers(DownloadSession session)
    {
        Dictionary<string, string> headers = session.Headers is null
            ? new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase)
            : session.Headers.ToDictionary(
                static pair => pair.Key,
                static pair => pair.Value,
                StringComparer.OrdinalIgnoreCase);
        if (!string.IsNullOrWhiteSpace(session.Cookie))
        {
            headers["Cookie"] = session.Cookie;
        }
        if (!string.IsNullOrWhiteSpace(session.Referer))
        {
            headers["Referer"] = session.Referer;
        }
        if (!string.IsNullOrWhiteSpace(session.UserAgent))
        {
            headers["User-Agent"] = session.UserAgent;
        }
        return headers;
    }

    private static DownloadRequest CreateBackendRequest(DownloadSession session)
    {
        lock (session.Sync)
        {
            return new DownloadRequest(
                session.Source,
                Path.GetDirectoryName(session.DestinationPath) ?? string.Empty,
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
                ConnectionCount: session.ConnectionCount,
                Method: session.Method,
                RequestBody: session.RequestBody,
                RequestBodyContentType: session.RequestBodyContentType,
                Priority: session.Priority,
                SourcePage: session.SourcePage,
                Mirrors: session.Mirrors.Skip(1).ToArray(),
                ExpectedChecksumAlgorithm: session.ExpectedChecksumAlgorithm,
                ExpectedChecksum: session.ExpectedChecksum,
                ExpectedLength: session.TotalBytes,
                BackendPreference: session.BackendPreference,
                AllowBackendFallback: session.AllowBackendFallback);
        }
    }

    private bool CanFallbackFromAria2(
        DownloadSession session,
        Exception exception,
        CancellationToken cancellationToken)
    {
        bool canFallback;
        lock (session.Sync)
        {
            canFallback = session.BackendTaskId is null;
        }

        return canFallback
            && IsNativeFallbackAllowed(session)
            && !cancellationToken.IsCancellationRequested
            && exception is (HttpRequestException
                or Aria2RpcException
                or InvalidOperationException);
    }

    private bool IsNativeFallbackAllowed(DownloadSession session)
    {
        bool sessionAllowsFallback;
        lock (session.Sync)
        {
            sessionAllowsFallback = session.AllowBackendFallback;
        }

        return sessionAllowsFallback
            && (_settingsService.Current.Aria2 ?? Aria2IntegrationSettings.Default)
                .Normalize()
                .AllowNativeFallback;
    }

    private static void SetNativeFallback(DownloadSession session, string reason)
    {
        lock (session.Sync)
        {
            session.Backend = DownloadBackendKind.Native;
            session.BackendTaskId = null;
            session.BackendDecisionReason = $"Fell back to the native backend: {reason}";
            session.ErrorMessage = null;
        }
    }

    private static bool PathsEqual(string left, string right)
    {
        try
        {
            return string.Equals(
                Path.GetFullPath(left),
                Path.GetFullPath(right),
                OperatingSystem.IsWindows() ? StringComparison.OrdinalIgnoreCase : StringComparison.Ordinal);
        }
        catch (Exception exception) when (exception is ArgumentException or NotSupportedException or PathTooLongException)
        {
            return false;
        }
    }

    private async Task RunDownloadAsync(DownloadSession session, CancellationToken cancellationToken)
    {
        string partialPath = GetPartialPath(session.DestinationPath);

        IDisposable? policyLease = null;
        IDisposable? queueLease = null;
        try
        {
            queueLease = await _queueConcurrencyLimiter
                .AcquireAsync(session.QueueId, () => ResolveQueueConcurrency(session.QueueId), cancellationToken)
                .ConfigureAwait(false);
            TransferPolicySnapshot policy = _transferPolicyRuntime.Current;
            if (policy.IsPaused)
            {
                lock (session.Sync)
                {
                    session.State = DownloadState.Queued;
                    session.ErrorMessage = policy.StatusMessage;
                }

                Publish(session, forcePersist: true);
                return;
            }

            policyLease = await _policyConcurrencyLimiter
                .AcquireAsync("global", ResolveConcurrentDownloadLimit, cancellationToken)
                .ConfigureAwait(false);

            int maximumAttempts = session.Method == "GET" ? _retryPolicy.MaximumAttempts : 1;
            while (true)
            {
                bool switchedMirror = false;
                for (int attempt = 1; attempt <= maximumAttempts; attempt++)
                {
                    try
                    {
                        TransferPolicySnapshot attemptPolicy = _transferPolicyRuntime.Current;
                        if (attemptPolicy.IsPaused)
                        {
                            lock (session.Sync)
                            {
                                session.State = DownloadState.Queued;
                                session.BytesPerSecond = 0;
                                session.ErrorMessage = attemptPolicy.StatusMessage;
                            }

                            Publish(session, forcePersist: true);
                            return;
                        }

                        using IDisposable hostLease = await _hostConcurrencyLimiter
                            .AcquireAsync(
                                session.Source.IdnHost,
                                () => _transferPolicyRuntime.Current.EffectiveProfile.MaxConcurrentPerHost,
                                cancellationToken)
                            .ConfigureAwait(false);
                        await ExecuteDownloadAttemptAsync(session, partialPath, cancellationToken).ConfigureAwait(false);
                        return;
                    }
                    catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
                    {
                        throw;
                    }
                    catch (Exception exception) when (DownloadRetryPolicy.IsTransient(exception))
                    {
                        if (attempt < maximumAttempts)
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
                            continue;
                        }

                        if (TrySwitchToNextMirror(session))
                        {
                            ResetTransferForMirror(session);
                            switchedMirror = true;
                            Publish(session, forcePersist: true);
                            break;
                        }

                        throw;
                    }
                }

                if (!switchedMirror)
                {
                    return;
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
            policyLease?.Dispose();
            queueLease?.Dispose();
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

        if (session.Source.Scheme is "ftp" or "ftps")
        {
            await ExecuteFtpDownloadAttemptAsync(session, partialPath, existingLength, cancellationToken)
                .ConfigureAwait(false);
            return;
        }

        if (session.Method == "GET" && existingLength == 0 && session.ConnectionCount > 1)
        {
            long? expectedSegmentedLength;
            lock (session.Sync)
            {
                expectedSegmentedLength = session.TotalBytes;
            }

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
                if (expectedSegmentedLength is long expectedLength
                    && expectedLength != segmentedResult.TotalBytes)
                {
                    throw new DownloadIntegrityException(
                        $"The remote file length changed. Expected {expectedLength}; received {segmentedResult.TotalBytes}.");
                }
                lock (session.Sync)
                {
                    session.DownloadedBytes = segmentedResult.TotalBytes;
                    session.TotalBytes = segmentedResult.TotalBytes;
                    session.BytesPerSecond = 0;
                    session.EntityTag = segmentedResult.EntityTag;
                    session.LastModified = segmentedResult.LastModified;
                    session.State = DownloadState.Finalizing;
                }

                await VerifyPartialIfExpectedAsync(session, partialPath, cancellationToken).ConfigureAwait(false);
                WriteFinalizationMarker(session, segmentedResult.TotalBytes);
                Publish(session, forcePersist: true);
                await CompleteFromPartialAsync(session, partialPath, segmentedResult.TotalBytes).ConfigureAwait(false);
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
                await VerifyPartialIfExpectedAsync(session, partialPath, cancellationToken).ConfigureAwait(false);
                lock (session.Sync)
                {
                    session.State = DownloadState.Finalizing;
                    session.BytesPerSecond = 0;
                }
                WriteFinalizationMarker(session, existingLength);
                Publish(session, forcePersist: true);
                await CompleteFromPartialAsync(session, partialPath, existingLength).ConfigureAwait(false);
                return;
            }
        }

        response.EnsureSuccessStatusCode();

        bool append = false;
        if (existingLength > 0 && response.StatusCode == HttpStatusCode.PartialContent)
        {
            ValidateContentRange(response, existingLength, session.TotalBytes);
            ValidateResumeValidators(response, session);
            append = true;
        }
        else if (existingLength > 0)
        {
            DownloadEngineLog.RangeIgnored(_logger, session.Id, existingLength, response.StatusCode);
            bool hadValidator;
            lock (session.Sync)
            {
                hadValidator = !string.IsNullOrWhiteSpace(session.EntityTag) || session.LastModified is not null;
            }

            if (hadValidator && File.Exists(partialPath))
            {
                string stalePath = ResolveUniqueArtifactPath(
                    TransferArtifactPaths.GetStalePartialPath(
                        session.DestinationPath,
                        DateTimeOffset.UtcNow));
                File.Move(partialPath, stalePath, overwrite: false);
                lock (session.Sync)
                {
                    session.RecoveryMessage = $"The remote file changed or rejected validated resume. The old partial data was preserved as '{stalePath}'.";
                }
            }

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

        if (totalBytes is long knownTotalBytes)
        {
            ValidateKnownLength(session, knownTotalBytes);
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
        await VerifyPartialIfExpectedAsync(session, partialPath, cancellationToken).ConfigureAwait(false);
        WriteFinalizationMarker(session, downloadedBytes);
        lock (session.Sync)
        {
            session.State = DownloadState.Finalizing;
            session.BytesPerSecond = 0;
        }

        Publish(session, forcePersist: true);
        await CompleteFromPartialAsync(session, partialPath, downloadedBytes).ConfigureAwait(false);
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

    private static void ValidateKnownLength(DownloadSession session, long actualLength)
    {
        long? expectedLength;
        lock (session.Sync)
        {
            expectedLength = session.TotalBytes;
        }

        if (expectedLength is long expected && expected != actualLength)
        {
            throw new DownloadIntegrityException(
                $"The remote file length changed. Expected {expected}; received {actualLength}.");
        }
    }

    private static void ValidateContentRange(
        HttpResponseMessage response,
        long expectedStart,
        long? expectedTotalBytes)
    {
        ContentRangeHeaderValue? contentRange = response.Content.Headers.ContentRange;
        if (contentRange?.From != expectedStart)
        {
            throw new DownloadIntegrityException(
                $"The server returned an invalid range start. Expected {expectedStart.ToString(System.Globalization.CultureInfo.InvariantCulture)}; received {contentRange?.From?.ToString(System.Globalization.CultureInfo.InvariantCulture) ?? "none"}.");
        }

        if (expectedTotalBytes is long expectedTotal
            && contentRange?.Length is long actualTotal
            && actualTotal != expectedTotal)
        {
            throw new DownloadIntegrityException(
                $"The remote file length changed while resuming. Expected {expectedTotal}; received {actualTotal}.");
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
        if (!string.IsNullOrWhiteSpace(expectedEntityTag))
        {
            if (string.IsNullOrWhiteSpace(responseEntityTag))
            {
                throw new DownloadIntegrityException("The server omitted the entity tag required to validate this resume.");
            }

            if (!string.Equals(expectedEntityTag, responseEntityTag, StringComparison.Ordinal))
            {
                throw new DownloadIntegrityException("The remote file entity tag changed while resuming.");
            }
        }

        DateTimeOffset? responseLastModified = response.Content.Headers.LastModified;
        if (expectedLastModified is DateTimeOffset expected)
        {
            if (responseLastModified is not DateTimeOffset actual)
            {
                throw new DownloadIntegrityException("The server omitted the modification date required to validate this resume.");
            }

            if (expected != actual)
            {
                throw new DownloadIntegrityException("The remote file modification date changed while resuming.");
            }
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

    private static void WriteFinalizationMarker(DownloadSession session, long downloadedBytes)
    {
        string destinationPath;
        string? checksumAlgorithm;
        string? checksum;
        lock (session.Sync)
        {
            destinationPath = session.DestinationPath;
            checksumAlgorithm = session.ActualChecksum is null ? null : session.ExpectedChecksumAlgorithm;
            checksum = session.ActualChecksum;
        }

        string markerPath = GetFinalizationMarkerPath(destinationPath);
        string temporaryPath = $"{markerPath}.tmp";
        FinalizationMarker marker = new(
            FinalizationMarker.CurrentVersion,
            downloadedBytes,
            checksumAlgorithm,
            checksum,
            DateTimeOffset.UtcNow);
        byte[] payload = Encoding.UTF8.GetBytes(JsonSerializer.Serialize(marker));
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

    private static async Task RecoverInterruptedFinalizationAsync(
        DownloadSession session,
        CancellationToken cancellationToken)
    {
        string markerPath = GetFinalizationMarkerPath(session.DestinationPath);
        if (!File.Exists(markerPath))
        {
            return;
        }

        FinalizationMarker marker = await ReadFinalizationMarkerAsync(markerPath, cancellationToken)
            .ConfigureAwait(false);
        string partialPath = GetPartialPath(session.DestinationPath);
        string candidatePath;
        if (File.Exists(session.DestinationPath))
        {
            candidatePath = session.DestinationPath;
        }
        else if (File.Exists(partialPath))
        {
            candidatePath = partialPath;
        }
        else
        {
            session.State = DownloadState.Failed;
            session.RecoveryRequired = true;
            session.RecoveryMessage = "Finalization was interrupted and the completed partial file is missing.";
            session.IntegrityStatus = DownloadIntegrityStatus.RecoveryRequired;
            session.ErrorMessage = session.RecoveryMessage;
            return;
        }

        long completedLength = new FileInfo(candidatePath).Length;
        if (completedLength != marker.ExpectedLength)
        {
            session.State = DownloadState.Failed;
            session.RecoveryRequired = true;
            session.RecoveryMessage = $"Interrupted finalization expected {marker.ExpectedLength} bytes, but found {completedLength}.";
            session.IntegrityStatus = DownloadIntegrityStatus.RecoveryRequired;
            session.ErrorMessage = session.RecoveryMessage;
            return;
        }

        if (!string.IsNullOrWhiteSpace(marker.ChecksumAlgorithm)
            && !string.IsNullOrWhiteSpace(marker.Checksum))
        {
            string actual = await DownloadChecksumService
                .ComputeAsync(candidatePath, marker.ChecksumAlgorithm, cancellationToken)
                .ConfigureAwait(false);
            if (!string.Equals(actual, marker.Checksum, StringComparison.OrdinalIgnoreCase))
            {
                session.State = DownloadState.Failed;
                session.RecoveryRequired = true;
                session.RecoveryMessage = "Interrupted finalization failed checksum verification.";
                session.IntegrityStatus = DownloadIntegrityStatus.Mismatch;
                session.ErrorMessage = session.RecoveryMessage;
                return;
            }
            session.ActualChecksum = actual;
            session.LastVerifiedAt = DateTimeOffset.UtcNow;
            session.IntegrityStatus = DownloadIntegrityStatus.Verified;
        }

        if (!string.Equals(candidatePath, session.DestinationPath, StringComparison.Ordinal))
        {
            File.Move(candidatePath, session.DestinationPath, overwrite: true);
        }

        session.DownloadedBytes = completedLength;
        session.TotalBytes ??= completedLength;
        session.State = DownloadState.Completed;
        session.ErrorMessage = null;
        session.RecoveryRequired = false;
        session.RecoveryMessage = "Recovered a download that was interrupted during finalization.";
        File.Delete(markerPath);
        string checkpointPath = TransferArtifactPaths.GetCheckpointPath(session.DestinationPath);
        if (File.Exists(checkpointPath))
        {
            File.Delete(checkpointPath);
        }
    }

    private static async Task<FinalizationMarker> ReadFinalizationMarkerAsync(
        string markerPath,
        CancellationToken cancellationToken)
    {
        string payload = await File.ReadAllTextAsync(markerPath, cancellationToken).ConfigureAwait(false);
        try
        {
            FinalizationMarker? marker = JsonSerializer.Deserialize<FinalizationMarker>(payload);
            if (marker is { Version: FinalizationMarker.CurrentVersion })
            {
                return marker;
            }
        }
        catch (JsonException)
        {
        }

        if (long.TryParse(
            payload,
            System.Globalization.NumberStyles.Integer,
            System.Globalization.CultureInfo.InvariantCulture,
            out long legacyLength))
        {
            return new FinalizationMarker(
                FinalizationMarker.CurrentVersion,
                legacyLength,
                null,
                null,
                DateTimeOffset.UtcNow);
        }

        throw new InvalidDataException("The finalization marker is invalid.");
    }

    private async Task CompleteFromPartialAsync(
        DownloadSession session,
        string partialPath,
        long downloadedBytes)
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

        await session.CheckpointGate.WaitAsync().ConfigureAwait(false);
        try
        {
            _checkpointStore.Delete(session.DestinationPath);
        }
        finally
        {
            session.CheckpointGate.Release();
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
        _ = PersistCheckpointSafelyAsync(session, forcePersist);
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

        // A matching transactional partial file is resumable state, not a destination collision.
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
                || File.Exists(TransferArtifactPaths.GetLegacyPartialPath(renamed))
                || File.Exists(TransferArtifactPaths.GetCheckpointPath(renamed))
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

    private async Task ExecuteFtpDownloadAttemptAsync(
        DownloadSession session,
        string partialPath,
        long existingLength,
        CancellationToken cancellationToken)
    {
        Stopwatch elapsed = Stopwatch.StartNew();
        long lastDownloaded = existingLength;
        DateTimeOffset lastProgress = DateTimeOffset.MinValue;
        lock (session.Sync)
        {
            session.DownloadedBytes = existingLength;
            session.State = DownloadState.Connecting;
            session.ErrorMessage = null;
        }
        Publish(session, forcePersist: true);

        long? expectedFtpLength;
        lock (session.Sync)
        {
            expectedFtpLength = session.TotalBytes;
        }

        FtpDownloadResult result = await _ftpDownloadClient.DownloadAsync(
            session.Source,
            partialPath,
            existingLength,
            session.Username,
            session.Password,
            async (downloaded, total) =>
            {
                DateTimeOffset now = DateTimeOffset.UtcNow;
                double seconds = Math.Max(0.001, elapsed.Elapsed.TotalSeconds);
                lock (session.Sync)
                {
                    session.DownloadedBytes = downloaded;
                    session.TotalBytes = total;
                    session.BytesPerSecond = Math.Max(0, downloaded - existingLength) / seconds;
                    session.State = DownloadState.Downloading;
                    session.ErrorMessage = null;
                }

                await ApplySpeedLimitAsync(session, elapsed, existingLength, cancellationToken)
                    .ConfigureAwait(false);
                if (now - lastProgress >= ProgressInterval || downloaded == total)
                {
                    lastProgress = now;
                    lastDownloaded = downloaded;
                    Publish(session, forcePersist: downloaded == total);
                }
            },
            cancellationToken).ConfigureAwait(false);

        long completedBytes = Math.Max(result.DownloadedBytes, lastDownloaded);
        long actualFtpLength = result.TotalBytes ?? completedBytes;
        if (expectedFtpLength is long expectedLength && expectedLength != actualFtpLength)
        {
            throw new DownloadIntegrityException(
                $"The remote file length changed. Expected {expectedLength}; received {actualFtpLength}.");
        }

        lock (session.Sync)
        {
            session.DownloadedBytes = completedBytes;
            session.TotalBytes = actualFtpLength;
            session.LastModified = result.LastModified;
            session.BytesPerSecond = 0;
            session.State = DownloadState.Finalizing;
        }

        await VerifyPartialIfExpectedAsync(session, partialPath, cancellationToken).ConfigureAwait(false);
        WriteFinalizationMarker(session, completedBytes);
        Publish(session, forcePersist: true);
        await CompleteFromPartialAsync(session, partialPath, completedBytes).ConfigureAwait(false);
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

    private int ResolveConcurrentDownloadLimit()
        => Math.Min(
            Math.Max(1, _settingsService.Current.MaxConcurrentDownloads),
            Math.Max(1, _transferPolicyRuntime.Current.EffectiveProfile.MaxConcurrentDownloads));

    private int ResolveQueueConcurrency(string queueId)
        => Math.Max(
            1,
            _settingsService.Current.Queues
                .FirstOrDefault(queue => string.Equals(queue.Id, queueId, StringComparison.Ordinal))
                ?.MaxConcurrentDownloads ?? _settingsService.Current.MaxConcurrentDownloads);

    private long ResolveSpeedLimit(DownloadSession session)
    {
        long queueLimit = _settingsService.Current.Queues
            .FirstOrDefault(queue => string.Equals(queue.Id, session.QueueId, StringComparison.Ordinal))
            ?.SpeedLimitBytesPerSecond ?? 0;
        long defaultLimit = _settingsService.Current.DefaultSpeedLimitBytesPerSecond;
        long requestLimit = session.SpeedLimitBytesPerSecond ?? 0;
        long policyLimit = _transferPolicyRuntime.Current.EffectiveProfile.SpeedLimitBytesPerSecond;
        long[] limits = [requestLimit, queueLimit, defaultLimit, policyLimit];
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
        return new QueueRuntimeSnapshot(
            activeQueues,
            runningCounts,
            DateTimeOffset.UtcNow,
            new HashSet<string>(_requestedQueues.Keys, StringComparer.Ordinal),
            new Dictionary<string, string>(_blockedQueues, StringComparer.Ordinal));
    }

    private void PublishQueueRuntime(bool reconcile = true)
    {
        if (reconcile)
        {
            ReconcileRequestedQueues();
        }

        QueueRuntimeChanged?.Invoke(this, CreateQueueRuntimeSnapshot());
    }

    private void RemoveQueueRequestTree(string queueId)
    {
        IReadOnlyList<DownloadQueueDefinition> queues = _settingsService.Current.Normalize().Queues;
        foreach (string rootId in _requestedQueueRoots.Keys.ToArray())
        {
            string[] startOrder = DownloadQueueDependencyGraph.GetStartOrder(rootId, queues);
            if (startOrder.Contains(queueId, StringComparer.Ordinal))
            {
                _requestedQueueRoots.TryRemove(rootId, out _);
            }
        }
    }

    private void RebuildRequestedQueues()
    {
        IReadOnlyList<DownloadQueueDefinition> queues = _settingsService.Current.Normalize().Queues;
        HashSet<string> desired = new(StringComparer.Ordinal);
        foreach (string rootId in _requestedQueueRoots.Keys)
        {
            desired.UnionWith(DownloadQueueDependencyGraph.GetStartOrder(rootId, queues));
        }

        foreach (string requestedId in desired)
        {
            _requestedQueues.TryAdd(requestedId, 0);
        }

        foreach (string removedId in _requestedQueues.Keys.Where(id => !desired.Contains(id)).ToArray())
        {
            _requestedQueues.TryRemove(removedId, out _);
            _blockedQueues.TryRemove(removedId, out _);
            if (_activeQueues.TryRemove(removedId, out _))
            {
                CancelQueueTransfers(removedId);
            }
        }
    }

    private void CancelQueueTransfers(string queueId)
    {
        foreach (DownloadSession session in _sessions.Values.Where(session =>
            string.Equals(session.QueueId, queueId, StringComparison.Ordinal)))
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
    }

    private void ReconcileRequestedQueues()
    {
        IReadOnlyList<DownloadQueueDefinition> queues = _settingsService.Current.Normalize().Queues;
        IReadOnlyList<DownloadSnapshot> downloads = _applicationState.Current.Downloads;
        foreach (string queueId in _requestedQueues.Keys)
        {
            QueueDependencyEvaluation evaluation = QueueDependencyEvaluator.Evaluate(queueId, queues, downloads);
            if (!evaluation.CanStart)
            {
                _blockedQueues[queueId] = evaluation.BlockedReason ?? "Queue dependencies are not satisfied.";
                if (_activeQueues.TryRemove(queueId, out _))
                {
                    CancelQueueTransfers(queueId);
                }

                continue;
            }

            _blockedQueues.TryRemove(queueId, out _);
            bool becameActive = _activeQueues.TryAdd(queueId, 0);
            if (!becameActive)
            {
                continue;
            }

            foreach (DownloadSession session in _sessions.Values
                .Where(session => string.Equals(session.QueueId, queueId, StringComparison.Ordinal)
                    && session.State == DownloadState.Queued)
                .OrderByDescending(static session => session.Priority)
                .ThenBy(static session => session.QueueOrder))
            {
                Start(session);
            }
        }
    }

    private void OnTransferPolicyChanged(object? sender, TransferPolicySnapshot snapshot)
    {
        _policyConcurrencyLimiter.NotifyLimitsChanged();
        _hostConcurrencyLimiter.NotifyLimitsChanged();
        if (snapshot.IsPaused)
        {
            foreach (DownloadSession session in _sessions.Values)
            {
                lock (session.Sync)
                {
                    if (session.State is DownloadState.Connecting or DownloadState.Downloading)
                    {
                        session.QueueStopRequested = true;
                        session.OperationCancellation?.Cancel();
                    }
                }
            }
        }
        else
        {
            ReconcileRequestedQueues();
            foreach (DownloadSession session in _sessions.Values
                .Where(session => session.State == DownloadState.Queued && IsQueueActive(session.QueueId))
                .OrderByDescending(static session => session.Priority)
                .ThenBy(static session => session.QueueOrder))
            {
                Start(session);
            }
        }

        PublishQueueRuntime(reconcile: false);
    }

    private void OnSettingsChanged(object? sender, ApplicationSettings settings)
    {
        _policyConcurrencyLimiter.NotifyLimitsChanged();
        _queueConcurrencyLimiter.NotifyLimitsChanged();
        _hostConcurrencyLimiter.NotifyLimitsChanged();
        RebuildRequestedQueues();
        ReconcileRequestedQueues();
        PublishQueueRuntime(reconcile: false);
    }

    private static void DeleteTransferArtifacts(string destinationPath)
    {
        string partialPath = GetPartialPath(destinationPath);
        if (File.Exists(partialPath))
        {
            File.Delete(partialPath);
        }

        string legacyPartialPath = TransferArtifactPaths.GetLegacyPartialPath(destinationPath);
        if (File.Exists(legacyPartialPath))
        {
            File.Delete(legacyPartialPath);
        }

        string checkpointPath = TransferArtifactPaths.GetCheckpointPath(destinationPath);
        if (File.Exists(checkpointPath))
        {
            File.Delete(checkpointPath);
        }
        DeleteIfExists($"{checkpointPath}.tmp");
        DeleteIfExists($"{partialPath}.merge");

        string markerPath = GetFinalizationMarkerPath(destinationPath);
        if (File.Exists(markerPath))
        {
            File.Delete(markerPath);
        }
        DeleteIfExists($"{markerPath}.tmp");

        string legacyMarkerPath = TransferArtifactPaths.GetLegacyFinalizationMarkerPath(destinationPath);
        if (File.Exists(legacyMarkerPath))
        {
            File.Delete(legacyMarkerPath);
        }

        string segmentDirectory = SegmentedDownloadExecutor.GetSegmentDirectory(destinationPath);
        if (Directory.Exists(segmentDirectory))
        {
            Directory.Delete(segmentDirectory, recursive: true);
        }
    }

    private static void DeleteIfExists(string path)
    {
        if (File.Exists(path))
        {
            File.Delete(path);
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

    private static void ValidateDownloadUri(Uri uri, string parameterName)
    {
        ArgumentNullException.ThrowIfNull(uri, parameterName);
        if (!ModernFeaturePolicy.IsSupportedDownloadUri(uri))
        {
            throw new ArgumentException(ModernFeaturePolicy.GetUnsupportedDownloadMessage(uri), parameterName);
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
        => TransferArtifactPaths.GetPartialPath(destinationPath);

    private static string GetFinalizationMarkerPath(string destinationPath)
        => TransferArtifactPaths.GetFinalizationMarkerPath(destinationPath);

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
                session.SourcePage,
                session.ExpectedChecksumAlgorithm,
                session.ExpectedChecksum,
                session.ActualChecksum,
                session.LastVerifiedAt,
                session.IntegrityStatus,
                session.RecoveryRequired,
                session.RecoveryMessage,
                session.Mirrors,
                session.BackendPreference,
                session.Backend,
                session.BackendTaskId,
                session.BackendDecisionReason,
                session.AllowBackendFallback);
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
                session.SourcePage,
                session.ExpectedChecksumAlgorithm,
                session.ExpectedChecksum,
                session.ActualChecksum,
                session.LastVerifiedAt,
                session.IntegrityStatus,
                session.RecoveryRequired,
                session.RecoveryMessage,
                session.Mirrors,
                session.BackendPreference,
                session.Backend,
                session.BackendTaskId,
                session.BackendDecisionReason,
                session.AllowBackendFallback);
        }
    }

    private async Task PersistCheckpointSafelyAsync(DownloadSession session, bool force)
    {
        try
        {
            await PersistCheckpointAsync(session, force, CancellationToken.None).ConfigureAwait(false);
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

    private async Task PersistCheckpointAsync(
        DownloadSession session,
        bool force,
        CancellationToken cancellationToken)
    {
        DateTimeOffset now = DateTimeOffset.UtcNow;
        long downloadedBytes;
        string destinationPath;
        lock (session.Sync)
        {
            if (session.Method != "GET" || session.State == DownloadState.Completed)
            {
                return;
            }

            downloadedBytes = session.DownloadedBytes;
            destinationPath = session.DestinationPath;
            if (!force
                && now - session.LastCheckpointAt < CheckpointInterval
                && downloadedBytes - session.LastCheckpointBytes < CheckpointByteInterval)
            {
                return;
            }
        }

        string partialPath = GetPartialPath(destinationPath);
        Dictionary<int, long> segmentLengths = GetSegmentLengths(destinationPath);
        long actualBytes = File.Exists(partialPath)
            ? new FileInfo(partialPath).Length
            : segmentLengths.Values.Sum();
        if (actualBytes == 0 && segmentLengths.Count == 0)
        {
            return;
        }

        await session.CheckpointGate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            ResumeCheckpoint checkpoint;
            lock (session.Sync)
            {
                if (session.State == DownloadState.Completed)
                {
                    return;
                }

                checkpoint = new ResumeCheckpoint(
                    ResumeCheckpoint.CurrentVersion,
                    session.Id,
                    session.Source,
                    session.DestinationPath,
                    actualBytes,
                    session.TotalBytes,
                    session.EntityTag,
                    session.LastModified,
                    session.ConnectionCount,
                    now,
                    session.ExpectedChecksumAlgorithm,
                    session.ExpectedChecksum,
                    session.Mirrors,
                    segmentLengths);
            }

            await _checkpointStore.SaveAsync(checkpoint, cancellationToken).ConfigureAwait(false);
            lock (session.Sync)
            {
                session.LastCheckpointAt = now;
                session.LastCheckpointBytes = actualBytes;
                if (session.IntegrityStatus == DownloadIntegrityStatus.Unknown)
                {
                    session.IntegrityStatus = DownloadIntegrityStatus.Checkpointed;
                }
            }
        }
        finally
        {
            session.CheckpointGate.Release();
        }
    }

    private static Dictionary<int, long> GetSegmentLengths(string destinationPath)
    {
        string directory = SegmentedDownloadExecutor.GetSegmentDirectory(destinationPath);
        if (!Directory.Exists(directory))
        {
            return new Dictionary<int, long>();
        }

        Dictionary<int, long> lengths = [];
        foreach (string path in Directory.EnumerateFiles(directory, "*.part", SearchOption.TopDirectoryOnly))
        {
            if (int.TryParse(
                Path.GetFileNameWithoutExtension(path),
                System.Globalization.NumberStyles.Integer,
                System.Globalization.CultureInfo.InvariantCulture,
                out int index))
            {
                lengths[index] = new FileInfo(path).Length;
            }
        }
        return lengths;
    }

    private static bool HasTransferArtifacts(string destinationPath)
        => File.Exists(GetPartialPath(destinationPath))
            || File.Exists($"{GetPartialPath(destinationPath)}.merge")
            || File.Exists(TransferArtifactPaths.GetCheckpointPath(destinationPath))
            || File.Exists(GetFinalizationMarkerPath(destinationPath))
            || Directory.Exists(SegmentedDownloadExecutor.GetSegmentDirectory(destinationPath));

    private static bool CanAdoptLegacySegmentArtifacts(string destinationPath)
    {
        string segmentDirectory = SegmentedDownloadExecutor.GetSegmentDirectory(destinationPath);
        return Directory.Exists(segmentDirectory)
            && !File.Exists(GetPartialPath(destinationPath))
            && !File.Exists($"{GetPartialPath(destinationPath)}.merge")
            && !File.Exists(TransferArtifactPaths.GetCheckpointPath(destinationPath))
            && !File.Exists(GetFinalizationMarkerPath(destinationPath))
            && Directory.EnumerateFiles(segmentDirectory, "*.part", SearchOption.TopDirectoryOnly).Any();
    }

    private static bool IsCheckpointCompatibleWithRequest(
        ResumeCheckpoint? checkpoint,
        Uri source,
        Uri[] mirrors,
        string destinationPath,
        long? expectedLength,
        string? expectedChecksumAlgorithm,
        string? expectedChecksum)
    {
        if (checkpoint is null)
        {
            return false;
        }

        bool hasPartialData = File.Exists(GetPartialPath(destinationPath))
            || GetSegmentLengths(destinationPath).Count > 0;
        if (!hasPartialData)
        {
            return false;
        }

        bool sourceMatches = checkpoint.Source == source
            || mirrors.Contains(checkpoint.Source)
            || (checkpoint.Mirrors?.Contains(source) ?? false);
        if (!sourceMatches
            || !string.Equals(
                Path.GetFullPath(checkpoint.DestinationPath),
                Path.GetFullPath(destinationPath),
                StringComparison.Ordinal))
        {
            return false;
        }

        if (expectedLength is long requestedLength
            && checkpoint.TotalBytes is long checkpointLength
            && requestedLength != checkpointLength)
        {
            return false;
        }

        if (!string.IsNullOrWhiteSpace(expectedChecksum)
            && !string.IsNullOrWhiteSpace(checkpoint.ExpectedChecksum)
            && (!string.Equals(
                    expectedChecksumAlgorithm,
                    checkpoint.ExpectedChecksumAlgorithm,
                    StringComparison.OrdinalIgnoreCase)
                || !string.Equals(
                    expectedChecksum,
                    checkpoint.ExpectedChecksum,
                    StringComparison.OrdinalIgnoreCase)))
        {
            return false;
        }

        return true;
    }

    private static void PreserveOrphanedTransferArtifacts(string destinationPath)
    {
        DateTimeOffset timestamp = DateTimeOffset.UtcNow;
        string partialPath = GetPartialPath(destinationPath);
        MoveArtifactIfExists(
            partialPath,
            TransferArtifactPaths.GetStalePartialPath(destinationPath, timestamp));
        MoveArtifactIfExists(
            $"{partialPath}.merge",
            $"{destinationPath}.stale-{timestamp:yyyyMMddHHmmss}.xdm.part.merge");
        MoveArtifactIfExists(
            TransferArtifactPaths.GetCheckpointPath(destinationPath),
            $"{destinationPath}.stale-{timestamp:yyyyMMddHHmmss}.xdm.resume.json");
        MoveArtifactIfExists(
            GetFinalizationMarkerPath(destinationPath),
            $"{destinationPath}.stale-{timestamp:yyyyMMddHHmmss}.xdm.finalizing");

        string segmentDirectory = SegmentedDownloadExecutor.GetSegmentDirectory(destinationPath);
        if (Directory.Exists(segmentDirectory))
        {
            Directory.Move(
                segmentDirectory,
                ResolveUniqueArtifactPath($"{destinationPath}.stale-{timestamp:yyyyMMddHHmmss}.segments"));
        }
    }

    private static void MoveArtifactIfExists(string sourcePath, string targetBasePath)
    {
        if (File.Exists(sourcePath))
        {
            File.Move(sourcePath, ResolveUniqueArtifactPath(targetBasePath));
        }
    }

    private static void MigrateLegacyArtifacts(string destinationPath)
    {
        string legacyPartial = TransferArtifactPaths.GetLegacyPartialPath(destinationPath);
        string currentPartial = GetPartialPath(destinationPath);
        if (File.Exists(legacyPartial) && !File.Exists(currentPartial))
        {
            File.Move(legacyPartial, currentPartial);
        }

        string legacyMarker = TransferArtifactPaths.GetLegacyFinalizationMarkerPath(destinationPath);
        string currentMarker = GetFinalizationMarkerPath(destinationPath);
        if (File.Exists(legacyMarker) && !File.Exists(currentMarker))
        {
            File.Move(legacyMarker, currentMarker);
        }
    }

    private static void ReconcileRecoveredState(
        DownloadSession session,
        ResumeCheckpoint? checkpoint,
        bool allowDownloadIdMismatch = false)
    {
        string partialPath = GetPartialPath(session.DestinationPath);
        long partialBytes = File.Exists(partialPath) ? new FileInfo(partialPath).Length : 0;
        long segmentBytes = GetSegmentLengths(session.DestinationPath).Values.Sum();
        long actualBytes = Math.Max(partialBytes, segmentBytes);

        if (checkpoint is not null)
        {
            bool sourceMatches = checkpoint.Source == session.Source
                || session.Mirrors.Contains(checkpoint.Source)
                || (checkpoint.Mirrors?.Contains(checkpoint.Source) ?? false);
            bool identityMatches = (allowDownloadIdMismatch
                    || string.Equals(checkpoint.DownloadId, session.Id, StringComparison.Ordinal))
                && sourceMatches
                && string.Equals(
                    Path.GetFullPath(checkpoint.DestinationPath),
                    Path.GetFullPath(session.DestinationPath),
                    StringComparison.Ordinal);
            if (!identityMatches)
            {
                session.RecoveryRequired = true;
                session.RecoveryMessage = "The resume checkpoint does not belong to this download.";
                session.IntegrityStatus = DownloadIntegrityStatus.RecoveryRequired;
                session.State = DownloadState.Paused;
                return;
            }

            Uri[] restoredMirrors = session.Mirrors
                .Concat(checkpoint.Mirrors ?? Array.Empty<Uri>())
                .Distinct()
                .ToArray();
            if (!restoredMirrors.Contains(checkpoint.Source))
            {
                restoredMirrors = new[] { checkpoint.Source }
                    .Concat(restoredMirrors)
                    .ToArray();
            }
            session.Source = checkpoint.Source;
            session.Mirrors = restoredMirrors;
            int restoredMirrorIndex = Array.FindIndex(restoredMirrors, mirror => mirror == session.Source);
            session.MirrorIndex = restoredMirrorIndex >= 0 ? restoredMirrorIndex + 1 : 0;
            session.TotalBytes = checkpoint.TotalBytes ?? session.TotalBytes;
            session.EntityTag ??= checkpoint.EntityTag;
            session.LastModified ??= checkpoint.LastModified;
            session.ExpectedChecksumAlgorithm ??= checkpoint.ExpectedChecksumAlgorithm;
            session.ExpectedChecksum ??= checkpoint.ExpectedChecksum;
        }

        if (session.State == DownloadState.Completed)
        {
            if (!File.Exists(session.DestinationPath))
            {
                session.State = DownloadState.Failed;
                session.RecoveryRequired = true;
                session.RecoveryMessage = "The completed file is missing. Repair can start a clean download.";
                session.IntegrityStatus = DownloadIntegrityStatus.RecoveryRequired;
            }
            return;
        }

        if (actualBytes > 0)
        {
            session.DownloadedBytes = actualBytes;
            session.LastCheckpointBytes = actualBytes;
            session.LastCheckpointAt = checkpoint?.UpdatedAt ?? DateTimeOffset.UtcNow;
            session.State = DownloadState.Paused;
            session.IntegrityStatus = DownloadIntegrityStatus.Checkpointed;
            session.RecoveryRequired = false;
            session.RecoveryMessage = "Recovered durable transfer state. Resume will validate the server before appending data.";
            if (session.TotalBytes is long total && actualBytes > total)
            {
                session.RecoveryRequired = true;
                session.RecoveryMessage = "The partial data is larger than the expected file and requires repair.";
                session.IntegrityStatus = DownloadIntegrityStatus.RecoveryRequired;
            }
        }
        else if (checkpoint is not null && checkpoint.DownloadedBytes > 0)
        {
            session.State = DownloadState.Failed;
            session.DownloadedBytes = 0;
            session.RecoveryRequired = true;
            session.RecoveryMessage = "A resume checkpoint exists, but its partial data is missing.";
            session.IntegrityStatus = DownloadIntegrityStatus.RecoveryRequired;
        }
    }

    private static bool TrySwitchToNextMirror(DownloadSession session)
    {
        lock (session.Sync)
        {
            if (session.MirrorIndex >= session.Mirrors.Length)
            {
                return false;
            }

            Uri previous = session.Source;
            Uri next = session.Mirrors[session.MirrorIndex++];
            session.Source = next;
            session.EntityTag = null;
            session.LastModified = null;
            session.ActualChecksum = null;
            session.LastVerifiedAt = null;
            session.IntegrityStatus = DownloadIntegrityStatus.Checkpointed;
            session.RecoveryRequired = false;
            session.RecoveryMessage = $"The source {previous.Host} failed; retrying from mirror {next.Host}.";
            session.ErrorMessage = session.RecoveryMessage;
            return true;
        }
    }

    private static void ResetTransferForMirror(DownloadSession session)
    {
        string destinationPath;
        lock (session.Sync)
        {
            destinationPath = session.DestinationPath;
            session.DownloadedBytes = 0;
            session.BytesPerSecond = 0;
        }
        DeleteTransferArtifacts(destinationPath);
    }

    private static async Task VerifyPartialIfExpectedAsync(
        DownloadSession session,
        string partialPath,
        CancellationToken cancellationToken)
    {
        string? algorithm;
        string? expected;
        lock (session.Sync)
        {
            algorithm = session.ExpectedChecksumAlgorithm;
            expected = session.ExpectedChecksum;
        }

        if (string.IsNullOrWhiteSpace(algorithm) || string.IsNullOrWhiteSpace(expected))
        {
            return;
        }

        string actual = await DownloadChecksumService
            .ComputeAsync(partialPath, algorithm, cancellationToken)
            .ConfigureAwait(false);
        bool matches = string.Equals(actual, expected, StringComparison.OrdinalIgnoreCase);
        lock (session.Sync)
        {
            session.ActualChecksum = actual;
            session.LastVerifiedAt = DateTimeOffset.UtcNow;
            session.IntegrityStatus = matches
                ? DownloadIntegrityStatus.Verified
                : DownloadIntegrityStatus.Mismatch;
            session.RecoveryRequired = !matches;
            session.RecoveryMessage = matches
                ? null
                : "The completed partial file failed checksum verification and was not finalized.";
        }

        if (!matches)
        {
            throw new DownloadIntegrityException(
                $"{algorithm} checksum mismatch. Expected {expected}; received {actual}.");
        }
    }

    private static string ResolveUniqueArtifactPath(string basePath)
    {
        if (!File.Exists(basePath) && !Directory.Exists(basePath))
        {
            return basePath;
        }

        for (int suffix = 2; suffix < 10_000; suffix++)
        {
            string candidate = $"{basePath}-{suffix}";
            if (!File.Exists(candidate) && !Directory.Exists(candidate))
            {
                return candidate;
            }
        }

        throw new IOException("Could not allocate a unique recovery artifact path.");
    }

    private static string? NormalizeExpectedAlgorithm(string? algorithm, string? checksum)
        => string.IsNullOrWhiteSpace(checksum)
            ? null
            : DownloadChecksumService.NormalizeAlgorithm(algorithm);

    private static string? NormalizeExpectedChecksum(string? algorithm, string? checksum)
        => string.IsNullOrWhiteSpace(checksum)
            ? null
            : DownloadChecksumService.NormalizeChecksum(
                checksum,
                DownloadChecksumService.NormalizeAlgorithm(algorithm));

    private static Uri[] NormalizeMirrors(
        Uri primary,
        IReadOnlyList<Uri>? mirrors)
    {
        if (mirrors is null || mirrors.Count == 0)
        {
            return [];
        }

        List<Uri> normalized = [];
        foreach (Uri mirror in mirrors)
        {
            ValidateDownloadUri(mirror, nameof(mirrors));
            if (mirror != primary && !normalized.Contains(mirror))
            {
                normalized.Add(mirror);
                if (normalized.Count == 31)
                {
                    break;
                }
            }
        }
        return normalized.ToArray();
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
            DateTimeOffset? updatedAt = null,
            string? expectedChecksumAlgorithm = null,
            string? expectedChecksum = null,
            string? actualChecksum = null,
            DateTimeOffset? lastVerifiedAt = null,
            DownloadIntegrityStatus integrityStatus = DownloadIntegrityStatus.Unknown,
            bool recoveryRequired = false,
            string? recoveryMessage = null,
            IReadOnlyList<Uri>? mirrors = null,
            DownloadBackendPreference backendPreference = DownloadBackendPreference.Automatic,
            DownloadBackendKind backend = DownloadBackendKind.Native,
            string? backendTaskId = null,
            string? backendDecisionReason = null,
            bool allowBackendFallback = true)
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
            ExpectedChecksumAlgorithm = expectedChecksumAlgorithm;
            ExpectedChecksum = expectedChecksum;
            ActualChecksum = actualChecksum;
            LastVerifiedAt = lastVerifiedAt;
            IntegrityStatus = integrityStatus;
            RecoveryRequired = recoveryRequired;
            RecoveryMessage = recoveryMessage;
            Uri[] normalizedMirrors = new[] { source }
                .Concat(mirrors ?? Array.Empty<Uri>())
                .Distinct()
                .Take(32)
                .ToArray();
            Mirrors = normalizedMirrors;
            BackendPreference = Enum.IsDefined(backendPreference)
                ? backendPreference
                : DownloadBackendPreference.Automatic;
            BackendTaskId = string.IsNullOrWhiteSpace(backendTaskId) ? null : backendTaskId.Trim();
            Backend = BackendTaskId is not null
                ? DownloadBackendKind.Aria2
                : Enum.IsDefined(backend)
                    ? backend
                    : DownloadBackendKind.Native;
            BackendDecisionReason = backendDecisionReason;
            AllowBackendFallback = allowBackendFallback;
            int currentMirrorIndex = Array.FindIndex(normalizedMirrors, mirror => mirror == source);
            MirrorIndex = currentMirrorIndex + 1;
        }

        public object Sync { get; } = new();

        public SemaphoreSlim CheckpointGate { get; } = new(1, 1);

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

        public string? ExpectedChecksumAlgorithm { get; set; }

        public string? ExpectedChecksum { get; set; }

        public string? ActualChecksum { get; set; }

        public DateTimeOffset? LastVerifiedAt { get; set; }

        public DownloadIntegrityStatus IntegrityStatus { get; set; }

        public bool RecoveryRequired { get; set; }

        public string? RecoveryMessage { get; set; }

        public Uri[] Mirrors { get; set; }

        public int MirrorIndex { get; set; }

        public DownloadBackendPreference BackendPreference { get; set; }

        public DownloadBackendKind Backend { get; set; }

        public string? BackendTaskId { get; set; }

        public string? BackendDecisionReason { get; set; }

        public bool AllowBackendFallback { get; set; }

        public TaskCompletionSource<Aria2TaskStatus>? Aria2TerminalSignal { get; set; }

        public bool Aria2FinalizationScheduled { get; set; }

        public DateTimeOffset LastCheckpointAt { get; set; } = DateTimeOffset.MinValue;

        public long LastCheckpointBytes { get; set; }

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
