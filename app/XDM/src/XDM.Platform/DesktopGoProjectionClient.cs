using System.Collections.Concurrent;
using System.Text.Json;
using XDM.Core.Downloads;
using XDM.Core.State;
using XDM.DownloadEngine;

namespace XDM.Platform;

public sealed record DesktopGoDownloadProjection(
    string Id,
    string FileName,
    string SourceUrl,
    string DestinationPath,
    long DownloadedBytes,
    long? TotalBytes,
    double BytesPerSecond,
    string State,
    long UpdatedAtEpochMs,
    string? ErrorMessage,
    string QueueId,
    string? CategoryId,
    int QueueOrder,
    int ConnectionCount,
    string Priority,
    string Backend,
    string BackendPreference,
    bool AllowBackendFallback,
    bool Archived);

public sealed class DesktopGoDownloadProjectionEventArgs(IReadOnlyList<DesktopGoDownloadProjection> downloads) : EventArgs
{
    public IReadOnlyList<DesktopGoDownloadProjection> Downloads { get; } = downloads;
}

public sealed class DesktopGoMediaProjectionEventArgs(JsonElement projection) : EventArgs
{
    public JsonElement Projection { get; } = projection;
}

public sealed class DesktopGoProjectionClient : IDisposable
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web)
    {
        PropertyNamingPolicy = JsonNamingPolicy.SnakeCaseLower,
        DictionaryKeyPolicy = JsonNamingPolicy.SnakeCaseLower
    };
    private readonly DesktopGoEngineHost _host;
    private readonly DesktopGoDownloadExecutionBroker _downloads;
    private readonly DesktopGoMediaBroker _media;
    private readonly ConcurrentDictionary<string, TaskCompletionSource<JsonElement>> _pending = new(StringComparer.Ordinal);
    private IReadOnlyList<DesktopGoDownloadProjection> _currentDownloads = [];
    private JsonElement? _currentMedia;
    private bool _disposed;

    public DesktopGoProjectionClient(
        DesktopGoEngineHost host,
        DesktopGoDownloadExecutionBroker downloads,
        DesktopGoMediaBroker media)
    {
        _host = host ?? throw new ArgumentNullException(nameof(host));
        _downloads = downloads ?? throw new ArgumentNullException(nameof(downloads));
        _media = media ?? throw new ArgumentNullException(nameof(media));
        _host.FrameReceived += OnFrameReceived;
    }

    public event EventHandler<DesktopGoDownloadProjectionEventArgs>? DownloadProjectionChanged;
    public event EventHandler<DesktopGoMediaProjectionEventArgs>? MediaProjectionChanged;

    public IReadOnlyList<DesktopGoDownloadProjection> CurrentDownloads => _currentDownloads;
    public JsonElement? CurrentMediaProjection => _currentMedia;
    public bool HasDownloadProjection { get; private set; }
    public bool HasMediaProjection { get; private set; }

    public async Task InitializeAsync(CancellationToken cancellationToken = default)
    {
        await _host.SubmitCommandAsync("desktop.ui.snapshot", JsonSerializer.SerializeToElement(new { }), cancellationToken).ConfigureAwait(false);
        await _host.SubmitCommandAsync("desktop.media.snapshot", JsonSerializer.SerializeToElement(new { }), cancellationToken).ConfigureAwait(false);
    }

    public async Task<string> AddDownloadAsync(DownloadRequest request, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(request);
        string fileName = request.ResolveFileName();
        DownloadRequest exact = request with { FileName = fileName };
        string requestRef = _downloads.Register(exact);
        string clientRequestId = "ui_" + Guid.NewGuid().ToString("N");
        long now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        object template = new
        {
            id = string.Empty,
            file_name = fileName,
            source_url = SafeProjectionUrl(request.Source),
            destination_path = Path.Combine(request.DestinationDirectory, fileName),
            downloaded_bytes = 0L,
            total_bytes = request.ExpectedLength,
            bytes_per_second = 0d,
            state = DownloadState.Queued.ToString(),
            updated_at_epoch_ms = now,
            error_message = (string?)null,
            queue_id = string.IsNullOrWhiteSpace(request.QueueId) ? "default" : request.QueueId,
            category_id = request.CategoryId,
            queue_order = 0,
            connection_count = request.ConnectionCount,
            priority = request.Priority.ToString(),
            backend = request.BackendPreference == DownloadBackendPreference.Aria2 ? DownloadBackendKind.Aria2.ToString() : DownloadBackendKind.Native.ToString(),
            backend_preference = request.BackendPreference.ToString(),
            allow_backend_fallback = request.AllowBackendFallback,
            archived = false
        };
        JsonElement result = await SubmitCorrelatedAsync(
            "desktop.ui.download_command",
            clientRequestId,
            JsonSerializer.SerializeToElement(new
            {
                client_request_id = clientRequestId,
                action = "add",
                download = template,
                request_ref = requestRef
            }, JsonOptions),
            cancellationToken).ConfigureAwait(false);
        EnsureAccepted(result, "Desktop add command was rejected.");
        return result.TryGetProperty("download_id", out JsonElement id) && !string.IsNullOrWhiteSpace(id.GetString())
            ? id.GetString()!
            : throw new InvalidDataException("Go desktop add result did not contain a download_id.");
    }

    public async Task ExecuteDownloadActionAsync(string action, string downloadId, CancellationToken cancellationToken = default)
    {
        if (action is not ("pause" or "resume" or "cancel" or "retry"))
        {
            throw new ArgumentOutOfRangeException(nameof(action));
        }
        ArgumentException.ThrowIfNullOrWhiteSpace(downloadId);
        string clientRequestId = "ui_" + Guid.NewGuid().ToString("N");
        JsonElement result = await SubmitCorrelatedAsync(
            "desktop.ui.download_command",
            clientRequestId,
            JsonSerializer.SerializeToElement(new
            {
                client_request_id = clientRequestId,
                action,
                download_id = downloadId
            }, JsonOptions),
            cancellationToken).ConfigureAwait(false);
        EnsureAccepted(result, $"Desktop {action} command was rejected.");
    }

    public Task ObserveExecutionAsync(ApplicationSnapshot snapshot, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(snapshot);
        object[] downloads = snapshot.Downloads.Select(static download => (object)new
        {
            id = download.Id,
            file_name = download.FileName,
            source_url = SafeProjectionUrl(download.Source),
            destination_path = download.DestinationPath,
            downloaded_bytes = download.DownloadedBytes,
            total_bytes = download.TotalBytes,
            bytes_per_second = download.BytesPerSecond,
            state = download.State.ToString(),
            updated_at_epoch_ms = download.UpdatedAt.ToUnixTimeMilliseconds(),
            error_message = download.ErrorMessage,
            queue_id = download.QueueId,
            category_id = download.CategoryId,
            queue_order = download.QueueOrder,
            connection_count = download.ConnectionCount,
            priority = download.Priority.ToString(),
            backend = download.Backend.ToString(),
            backend_preference = download.BackendPreference.ToString(),
            allow_backend_fallback = download.AllowBackendFallback,
            archived = download.IsArchived
        }).ToArray();
        return _host.SubmitCommandAsync(
            "desktop.ui.execution_observation",
            JsonSerializer.SerializeToElement(new { downloads }, JsonOptions),
            cancellationToken);
    }

    public void RegisterMediaCapture(string captureId, DesktopMediaRequestHandoff handoff)
        => _media.RegisterCapture(captureId, handoff);

    public void RegisterMediaVariant(string variantId, DesktopMediaRequestHandoff handoff)
        => _media.RegisterVariant(variantId, handoff);

    public async Task CaptureMediaAsync(JsonElement payload, string clientRequestId, CancellationToken cancellationToken = default)
    {
        JsonElement result = await SubmitCorrelatedAsync("desktop.media.capture", clientRequestId, payload, cancellationToken).ConfigureAwait(false);
        EnsureAccepted(result, "Desktop media capture was rejected by Go.");
    }

    public async Task SelectMediaAsync(
        string captureId,
        string? videoVariantId,
        string? audioVariantId,
        string? subtitleVariantId,
        CancellationToken cancellationToken = default)
    {
        string clientRequestId = "media_" + Guid.NewGuid().ToString("N");
        JsonElement result = await SubmitCorrelatedAsync(
            "desktop.media.select",
            clientRequestId,
            JsonSerializer.SerializeToElement(new
            {
                client_request_id = clientRequestId,
                capture_id = captureId,
                selection = new
                {
                    video_variant_id = videoVariantId ?? string.Empty,
                    audio_variant_id = audioVariantId ?? string.Empty,
                    subtitle_variant_id = subtitleVariantId ?? string.Empty
                }
            }, JsonOptions),
            cancellationToken).ConfigureAwait(false);
        EnsureAccepted(result, "Desktop media selection was rejected by Go.");
    }

    public async Task<string> ExecuteMediaAsync(
        string captureId,
        string outputPath,
        long? durationMs,
        long? maximumBytes,
        CancellationToken cancellationToken = default)
    {
        string clientRequestId = "media_" + Guid.NewGuid().ToString("N");
        JsonElement result = await SubmitCorrelatedAsync(
            "desktop.media.execute",
            clientRequestId,
            JsonSerializer.SerializeToElement(new
            {
                client_request_id = clientRequestId,
                capture_id = captureId,
                output_path = outputPath,
                duration_ms = durationMs,
                maximum_bytes = maximumBytes
            }, JsonOptions),
            cancellationToken).ConfigureAwait(false);
        EnsureAccepted(result, "Desktop media execution was rejected by Go.");
        return result.TryGetProperty("output_path", out JsonElement path) && !string.IsNullOrWhiteSpace(path.GetString())
            ? path.GetString()!
            : outputPath;
    }

    private async Task<JsonElement> SubmitCorrelatedAsync(
        string kind,
        string clientRequestId,
        JsonElement payload,
        CancellationToken cancellationToken)
    {
        TaskCompletionSource<JsonElement> completion = new(TaskCreationOptions.RunContinuationsAsynchronously);
        if (!_pending.TryAdd(clientRequestId, completion))
        {
            throw new InvalidOperationException("Duplicate desktop Go client request id.");
        }
        try
        {
            await _host.SubmitCommandAsync(kind, payload, cancellationToken).ConfigureAwait(false);
            return await completion.Task.WaitAsync(cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            _pending.TryRemove(clientRequestId, out _);
        }
    }

    private void OnFrameReceived(object? sender, DesktopGoFrameEventArgs args)
    {
        switch (args.Kind)
        {
            case "desktop.ui.projection":
            {
                DesktopUiProjectionWire? projection = args.Payload.Deserialize<DesktopUiProjectionWire>(JsonOptions);
                _currentDownloads = projection?.Downloads ?? [];
                HasDownloadProjection = true;
                DownloadProjectionChanged?.Invoke(this, new DesktopGoDownloadProjectionEventArgs(_currentDownloads));
                break;
            }
            case "desktop.media.projection":
                _currentMedia = args.Payload.Clone();
                HasMediaProjection = true;
                MediaProjectionChanged?.Invoke(this, new DesktopGoMediaProjectionEventArgs(_currentMedia.Value));
                break;
            case "desktop.ui.command_result":
            case "desktop.media.command_result":
                CompleteCorrelated(args.Payload);
                break;
        }
    }

    private void CompleteCorrelated(JsonElement payload)
    {
        string id = payload.TryGetProperty("client_request_id", out JsonElement element) ? element.GetString() ?? string.Empty : string.Empty;
        if (_pending.TryRemove(id, out TaskCompletionSource<JsonElement>? completion))
        {
            completion.TrySetResult(payload.Clone());
        }
    }

    private static void EnsureAccepted(JsonElement result, string fallback)
    {
        if (result.TryGetProperty("ok", out JsonElement ok) && ok.ValueKind == JsonValueKind.True)
        {
            return;
        }
        string message = result.TryGetProperty("message", out JsonElement detail) ? detail.GetString() ?? fallback : fallback;
        throw new InvalidOperationException(message);
    }

    private static string SafeProjectionUrl(Uri source)
    {
        if (!source.IsAbsoluteUri)
        {
            return source.ToString();
        }
        UriBuilder builder = new(source) { UserName = string.Empty, Password = string.Empty, Query = string.Empty, Fragment = string.Empty };
        return builder.Uri.AbsoluteUri;
    }

    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }
        _disposed = true;
        _host.FrameReceived -= OnFrameReceived;
        foreach (TaskCompletionSource<JsonElement> completion in _pending.Values)
        {
            completion.TrySetException(new ObjectDisposedException(nameof(DesktopGoProjectionClient)));
        }
        _pending.Clear();
        DownloadProjectionChanged = null;
        MediaProjectionChanged = null;
        GC.SuppressFinalize(this);
    }

    private sealed record DesktopUiProjectionWire(long Revision, IReadOnlyList<DesktopGoDownloadProjection> Downloads);
}
