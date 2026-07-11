using System.Collections.Concurrent;
using System.Diagnostics;
using System.Net;
using System.Net.Http.Headers;
using Microsoft.Extensions.Logging;
using XDM.Core.Downloads;
using XDM.Core.Persistence;
using XDM.Core.State;
using XDM.DownloadEngine.Logging;

namespace XDM.DownloadEngine;

public sealed class DownloadManager : IDownloadManager, IDisposable
{
    private const int BufferSize = 64 * 1024;
    private static readonly TimeSpan ProgressInterval = TimeSpan.FromMilliseconds(150);
    private static readonly TimeSpan PersistenceInterval = TimeSpan.FromSeconds(2);

    private readonly HttpClient _httpClient;
    private readonly IApplicationState _applicationState;
    private readonly IDownloadHistoryStore _historyStore;
    private readonly ILogger<DownloadManager> _logger;
    private readonly ConcurrentDictionary<string, DownloadSession> _sessions = new(StringComparer.Ordinal);
    private readonly SemaphoreSlim _persistenceGate = new(1, 1);
    private DateTimeOffset _lastPersistence = DateTimeOffset.MinValue;
    private bool _disposed;

    public DownloadManager(
        HttpClient httpClient,
        IApplicationState applicationState,
        IDownloadHistoryStore historyStore,
        ILogger<DownloadManager> logger)
    {
        _httpClient = httpClient;
        _applicationState = applicationState;
        _historyStore = historyStore;
        _logger = logger;
    }

    public async Task InitializeAsync(CancellationToken cancellationToken = default)
    {
        ThrowIfDisposed();
        IReadOnlyList<PersistedDownload> persisted = await _historyStore
            .LoadAsync(cancellationToken)
            .ConfigureAwait(false);

        foreach (PersistedDownload item in persisted)
        {
            DownloadState restoredState = item.State is DownloadState.Connecting
                or DownloadState.Downloading
                or DownloadState.Finalizing
                    ? DownloadState.Paused
                    : item.State;

            DownloadSession session = new(
                item.Id,
                item.Source,
                item.DestinationPath,
                restoredState,
                item.DownloadedBytes,
                item.TotalBytes,
                item.ErrorMessage);

            _sessions[item.Id] = session;
            _applicationState.UpsertDownload(CreateSnapshot(session));
        }
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

        ArgumentException.ThrowIfNullOrWhiteSpace(request.DestinationDirectory);
        Directory.CreateDirectory(request.DestinationDirectory);

        string fileName = request.ResolveFileName();
        string destinationPath = Path.Combine(request.DestinationDirectory, fileName);
        DownloadSession session = new(
            Guid.NewGuid().ToString("N"),
            request.Source,
            destinationPath,
            DownloadState.Queued,
            0,
            null,
            null);

        if (!_sessions.TryAdd(session.Id, session))
        {
            throw new InvalidOperationException("Could not register the new download.");
        }

        Publish(session, forcePersist: true);
        Start(session);
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

    public async Task RemoveAsync(
        string downloadId,
        bool deletePartialFile = false,
        CancellationToken cancellationToken = default)
    {
        DownloadSession session = GetSession(downloadId);
        cancellationToken.ThrowIfCancellationRequested();

        lock (session.Sync)
        {
            session.PauseRequested = false;
            session.OperationCancellation?.Cancel();
        }

        _sessions.TryRemove(downloadId, out _);
        _applicationState.RemoveDownload(downloadId);

        if (deletePartialFile)
        {
            string partialPath = GetPartialPath(session.DestinationPath);
            if (File.Exists(partialPath))
            {
                File.Delete(partialPath);
            }
        }

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

        _persistenceGate.Dispose();
    }

    private void Start(DownloadSession session)
    {
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

        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(session.DestinationPath)!);
            long existingLength = File.Exists(partialPath) ? new FileInfo(partialPath).Length : 0;

            using HttpRequestMessage request = new(HttpMethod.Get, session.Source);
            if (existingLength > 0)
            {
                request.Headers.Range = new RangeHeaderValue(existingLength, null);
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

            bool append = existingLength > 0 && response.StatusCode == HttpStatusCode.PartialContent;
            if (!append)
            {
                existingLength = 0;
            }

            long? totalBytes = response.Content.Headers.ContentRange?.Length;
            if (totalBytes is null && response.Content.Headers.ContentLength is long contentLength)
            {
                totalBytes = existingLength + contentLength;
            }

            lock (session.Sync)
            {
                session.DownloadedBytes = existingLength;
                session.TotalBytes = totalBytes;
                session.State = DownloadState.Downloading;
            }

            Publish(session, forcePersist: false);

            await using Stream source = await response.Content
                .ReadAsStreamAsync(cancellationToken)
                .ConfigureAwait(false);
            await using FileStream destination = new(
                partialPath,
                append ? FileMode.Append : FileMode.Create,
                FileAccess.Write,
                FileShare.Read,
                BufferSize,
                FileOptions.Asynchronous | FileOptions.SequentialScan);

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

                DateTimeOffset now = DateTimeOffset.UtcNow;
                if (now - lastProgress >= ProgressInterval)
                {
                    lastProgress = now;
                    Publish(session, forcePersist: false);
                }
            }

            await destination.FlushAsync(cancellationToken).ConfigureAwait(false);
            lock (session.Sync)
            {
                session.State = DownloadState.Finalizing;
                session.BytesPerSecond = 0;
            }

            Publish(session, forcePersist: false);
            CompleteFromPartial(session, partialPath, session.DownloadedBytes);
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            lock (session.Sync)
            {
                session.State = session.PauseRequested
                    ? DownloadState.Paused
                    : DownloadState.Cancelled;
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
    }

    private void CompleteFromPartial(DownloadSession session, string partialPath, long downloadedBytes)
    {
        File.Move(partialPath, session.DestinationPath, overwrite: true);
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

    private static string GetPartialPath(string destinationPath)
        => $"{destinationPath}.part";

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
                DateTimeOffset.UtcNow,
                session.ErrorMessage);
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
                DateTimeOffset.UtcNow,
                session.ErrorMessage);
        }
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
            string? errorMessage)
        {
            Id = id;
            Source = source;
            DestinationPath = destinationPath;
            State = state;
            DownloadedBytes = downloadedBytes;
            TotalBytes = totalBytes;
            ErrorMessage = errorMessage;
        }

        public object Sync { get; } = new();

        public string Id { get; }

        public Uri Source { get; }

        public string DestinationPath { get; }

        public DownloadState State { get; set; }

        public long DownloadedBytes { get; set; }

        public long? TotalBytes { get; set; }

        public double BytesPerSecond { get; set; }

        public string? ErrorMessage { get; set; }

        public bool PauseRequested { get; set; }

        public CancellationTokenSource? OperationCancellation { get; set; }

        public Task? ActiveTask { get; set; }
    }
}
