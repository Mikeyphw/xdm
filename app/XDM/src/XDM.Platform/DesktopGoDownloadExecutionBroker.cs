using System.Collections.Concurrent;
using System.Net.Http.Headers;
using System.Text.Json;
using XDM.Core.Downloads;
using XDM.Core.Settings;
using XDM.DownloadEngine;
using XDM.DownloadEngine.Aria2;

namespace XDM.Platform;

public sealed class DesktopGoExecutionSnapshotEventArgs(IReadOnlyList<DesktopGoDownloadProjection> downloads) : EventArgs
{
    public IReadOnlyList<DesktopGoDownloadProjection> Downloads { get; } = downloads;
}

/// <summary>
/// XGO-80 desktop transfer mechanism. Go owns download identity, admission, retry/queue policy and
/// durable state. This host adapter performs only the requested native I/O or stateless aria2 RPC.
/// </summary>
public sealed class DesktopGoDownloadExecutionBroker
{
    private readonly HttpClient _http;
    private readonly ISettingsService _settings;
    private readonly ConcurrentDictionary<string, DownloadRequest> _pending = new(StringComparer.Ordinal);
    private readonly ConcurrentDictionary<string, NativeTransfer> _native = new(StringComparer.Ordinal);

    public DesktopGoDownloadExecutionBroker(HttpClient http, ISettingsService settings)
    {
        _http = http ?? throw new ArgumentNullException(nameof(http));
        _settings = settings ?? throw new ArgumentNullException(nameof(settings));
    }

    public event EventHandler<DesktopGoExecutionSnapshotEventArgs>? SnapshotChanged;

    public string Register(DownloadRequest request)
    {
        ArgumentNullException.ThrowIfNull(request);
        string reference = "req_" + Guid.NewGuid().ToString("N");
        _pending[reference] = request;
        return reference;
    }

    public async Task<JsonElement> ExecuteAsync(JsonElement payload, CancellationToken cancellationToken = default)
    {
        string action = payload.GetProperty("action").GetString() ?? string.Empty;
        string backend = payload.TryGetProperty("backend", out JsonElement backendElement)
            ? backendElement.GetString() ?? string.Empty
            : string.Empty;
        string? backendTaskId = payload.TryGetProperty("backend_task_id", out JsonElement taskElement)
            ? taskElement.GetString()
            : null;

        if (string.Equals(backend, DownloadBackendKind.Aria2.ToString(), StringComparison.OrdinalIgnoreCase))
        {
            return await ExecuteAria2Async(action, payload, backendTaskId, cancellationToken).ConfigureAwait(false);
        }

        switch (action)
        {
            case "add":
            {
                string requestRef = payload.GetProperty("request_ref").GetString() ?? string.Empty;
                string id = RequireDownloadId(payload);
                if (!_pending.TryRemove(requestRef, out DownloadRequest? request))
                {
                    return Reject("request_ref_missing", "The exact desktop download request is no longer available.");
                }
                if (request.BackendPreference == DownloadBackendPreference.Aria2)
                {
                    return await AddAria2Async(id, request, cancellationToken).ConfigureAwait(false);
                }
                NativeTransfer transfer = new(id, request);
                if (!_native.TryAdd(id, transfer))
                {
                    return Reject("download_exists", "A desktop host transfer with this Go identity already exists.");
                }
                StartNative(transfer);
                return JsonSerializer.SerializeToElement(new { ok = true, status = "started", download_id = id });
            }
            case "pause":
                PauseNative(RequireDownloadId(payload));
                break;
            case "resume":
            case "retry":
                ResumeNative(RequireDownloadId(payload));
                break;
            case "cancel":
                CancelNative(RequireDownloadId(payload));
                break;
            default:
                return Reject("unsupported_download_action", "The requested desktop download action is not allowlisted.");
        }
        return JsonSerializer.SerializeToElement(new { ok = true, status = "accepted" });
    }

    public Task<DownloadShutdownReport> PrepareForShutdownAsync(CancellationToken cancellationToken = default)
    {
        cancellationToken.ThrowIfCancellationRequested();
        DateTimeOffset started = DateTimeOffset.UtcNow;
        string[] active = _native.Values
            .Where(static transfer => transfer.State is DownloadState.Connecting or DownloadState.Downloading or DownloadState.Finalizing)
            .Select(static transfer => transfer.Id)
            .OrderBy(static id => id, StringComparer.Ordinal)
            .ToArray();
        foreach (string id in active)
        {
            PauseNative(id);
        }
        return Task.FromResult(new DownloadShutdownReport(active, active.Length, active.Length, [], started, DateTimeOffset.UtcNow));
    }

    private async Task<JsonElement> ExecuteAria2Async(
        string action,
        JsonElement payload,
        string? backendTaskId,
        CancellationToken cancellationToken)
    {
        if (action == "add")
        {
            string requestRef = payload.GetProperty("request_ref").GetString() ?? string.Empty;
            string id = RequireDownloadId(payload);
            if (!_pending.TryRemove(requestRef, out DownloadRequest? request))
            {
                return Reject("request_ref_missing", "The exact desktop download request is no longer available.");
            }
            return await AddAria2Async(id, request, cancellationToken).ConfigureAwait(false);
        }
        if (string.IsNullOrWhiteSpace(backendTaskId))
        {
            return Reject("aria2_task_ref_missing", "Go did not provide the authoritative aria2 task reference.");
        }
        Aria2RpcClient rpc = CreateAria2Client();
        switch (action)
        {
            case "pause":
                await rpc.PauseAsync(backendTaskId, cancellationToken).ConfigureAwait(false);
                break;
            case "resume":
            case "retry":
                await rpc.ResumeAsync(backendTaskId, cancellationToken).ConfigureAwait(false);
                break;
            case "cancel":
                await rpc.RemoveAsync(backendTaskId, cancellationToken).ConfigureAwait(false);
                break;
            default:
                return Reject("unsupported_download_action", "The requested aria2 action is not allowlisted.");
        }
        return JsonSerializer.SerializeToElement(new { ok = true, status = "accepted", backend_task_id = backendTaskId });
    }

    private async Task<JsonElement> AddAria2Async(string id, DownloadRequest request, CancellationToken cancellationToken)
    {
        Aria2IntegrationSettings settings = (_settings.Current.Aria2 ?? Aria2IntegrationSettings.Default).Normalize();
        if (!settings.Enabled)
        {
            return Reject("aria2_disabled", "aria2 is not enabled in desktop settings.");
        }
        Aria2RpcClient rpc = new(_http, settings.GetRpcUri(), settings.RpcSecret);
        string gid = await rpc.AddAsync(new Aria2AddRequest(
            request.Source,
            request.DestinationDirectory,
            request.ResolveFileName(),
            request.Headers,
            request.Username,
            request.Password,
            request.SpeedLimitBytesPerSecond)
        {
            Mirrors = request.Mirrors,
            ExpectedChecksumAlgorithm = request.ExpectedChecksumAlgorithm,
            ExpectedChecksum = request.ExpectedChecksum,
            ConnectionCount = request.ConnectionCount,
            ContinueDownloads = settings.ContinueDownloads,
            CheckCertificate = settings.CheckCertificate
        }, cancellationToken).ConfigureAwait(false);
        return JsonSerializer.SerializeToElement(new
        {
            ok = true,
            status = "started",
            download_id = id,
            backend_task_id = gid
        });
    }

    private Aria2RpcClient CreateAria2Client()
    {
        Aria2IntegrationSettings settings = (_settings.Current.Aria2 ?? Aria2IntegrationSettings.Default).Normalize();
        if (!settings.Enabled)
        {
            throw new InvalidOperationException("aria2 is not enabled in desktop settings.");
        }
        return new Aria2RpcClient(_http, settings.GetRpcUri(), settings.RpcSecret);
    }

    private void StartNative(NativeTransfer transfer)
    {
        lock (transfer.Sync)
        {
            if (transfer.Task is { IsCompleted: false })
            {
                return;
            }
            transfer.Cancellation?.Dispose();
            transfer.Cancellation = new CancellationTokenSource();
            transfer.State = DownloadState.Connecting;
            transfer.ErrorMessage = null;
            PublishSnapshot();
            transfer.Task = Task.Run(() => RunNativeAsync(transfer, transfer.Cancellation.Token), CancellationToken.None);
        }
    }

    private async Task RunNativeAsync(NativeTransfer transfer, CancellationToken cancellationToken)
    {
        string destination = Path.Combine(transfer.Request.DestinationDirectory, transfer.Request.ResolveFileName());
        string partial = destination + ".xdm.part";
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(destination)!);
            long existing = File.Exists(partial) ? new FileInfo(partial).Length : 0;
            using HttpRequestMessage request = new(new HttpMethod(transfer.Request.Method), transfer.Request.Source);
            if (existing > 0 && string.Equals(transfer.Request.Method, "GET", StringComparison.OrdinalIgnoreCase))
            {
                request.Headers.Range = new RangeHeaderValue(existing, null);
            }
            ApplyHeaders(request, transfer.Request);
            if (transfer.Request.RequestBody is { Length: > 0 })
            {
                request.Content = new ByteArrayContent(transfer.Request.RequestBody);
                if (!string.IsNullOrWhiteSpace(transfer.Request.RequestBodyContentType))
                {
                    request.Content.Headers.ContentType = MediaTypeHeaderValue.Parse(transfer.Request.RequestBodyContentType);
                }
            }
            using HttpResponseMessage response = await _http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken).ConfigureAwait(false);
            response.EnsureSuccessStatusCode();
            if (existing > 0 && response.StatusCode != System.Net.HttpStatusCode.PartialContent)
            {
                existing = 0;
                File.Delete(partial);
            }
            long? total = response.Content.Headers.ContentLength is long contentLength ? existing + contentLength : transfer.Request.ExpectedLength;
            lock (transfer.Sync)
            {
                transfer.TotalBytes = total;
                transfer.DownloadedBytes = existing;
                transfer.State = DownloadState.Downloading;
            }
            PublishSnapshot();
            await using Stream input = await response.Content.ReadAsStreamAsync(cancellationToken).ConfigureAwait(false);
            await using FileStream output = new(partial, FileMode.Append, FileAccess.Write, FileShare.Read, 128 * 1024, FileOptions.Asynchronous | FileOptions.SequentialScan);
            byte[] buffer = new byte[128 * 1024];
            while (true)
            {
                int read = await input.ReadAsync(buffer, cancellationToken).ConfigureAwait(false);
                if (read == 0)
                {
                    break;
                }
                await output.WriteAsync(buffer.AsMemory(0, read), cancellationToken).ConfigureAwait(false);
                lock (transfer.Sync)
                {
                    transfer.DownloadedBytes += read;
                }
                PublishSnapshot();
            }
            await output.FlushAsync(cancellationToken).ConfigureAwait(false);
            lock (transfer.Sync)
            {
                transfer.State = DownloadState.Finalizing;
            }
            PublishSnapshot();
            File.Move(partial, destination, true);
            lock (transfer.Sync)
            {
                transfer.State = DownloadState.Completed;
                transfer.DownloadedBytes = new FileInfo(destination).Length;
                transfer.TotalBytes ??= transfer.DownloadedBytes;
            }
            PublishSnapshot();
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            PublishSnapshot();
        }
        catch (Exception exception) when (exception is IOException or HttpRequestException or UnauthorizedAccessException or InvalidOperationException)
        {
            lock (transfer.Sync)
            {
                transfer.State = DownloadState.Failed;
                transfer.ErrorMessage = exception.Message;
            }
            PublishSnapshot();
        }
    }

    private void PauseNative(string id)
    {
        if (!_native.TryGetValue(id, out NativeTransfer? transfer))
        {
            return;
        }
        lock (transfer.Sync)
        {
            transfer.State = DownloadState.Paused;
            transfer.Cancellation?.Cancel();
        }
        PublishSnapshot();
    }

    private void ResumeNative(string id)
    {
        if (!_native.TryGetValue(id, out NativeTransfer? transfer))
        {
            return;
        }
        StartNative(transfer);
    }

    private void CancelNative(string id)
    {
        if (!_native.TryGetValue(id, out NativeTransfer? transfer))
        {
            return;
        }
        lock (transfer.Sync)
        {
            transfer.State = DownloadState.Cancelled;
            transfer.Cancellation?.Cancel();
        }
        PublishSnapshot();
    }

    private void PublishSnapshot()
    {
        DesktopGoDownloadProjection[] snapshot = _native.Values
            .OrderBy(static transfer => transfer.Id, StringComparer.Ordinal)
            .Select(static transfer => transfer.ToProjection())
            .ToArray();
        SnapshotChanged?.Invoke(this, new DesktopGoExecutionSnapshotEventArgs(snapshot));
    }

    private static void ApplyHeaders(HttpRequestMessage message, DownloadRequest request)
    {
        foreach ((string key, string value) in request.Headers ?? new Dictionary<string, string>())
        {
            _ = message.Headers.TryAddWithoutValidation(key, value);
        }
        if (!string.IsNullOrWhiteSpace(request.Cookie))
        {
            _ = message.Headers.TryAddWithoutValidation("Cookie", request.Cookie);
        }
        if (!string.IsNullOrWhiteSpace(request.Referer))
        {
            message.Headers.Referrer = Uri.TryCreate(request.Referer, UriKind.Absolute, out Uri? referer) ? referer : null;
        }
        if (!string.IsNullOrWhiteSpace(request.UserAgent))
        {
            _ = message.Headers.TryAddWithoutValidation("User-Agent", request.UserAgent);
        }
    }

    private static string RequireDownloadId(JsonElement payload)
    {
        string id = payload.TryGetProperty("download_id", out JsonElement element) ? element.GetString() ?? string.Empty : string.Empty;
        if (string.IsNullOrWhiteSpace(id))
        {
            throw new InvalidDataException("Desktop download command requires a download_id.");
        }
        return id;
    }

    private static JsonElement Reject(string code, string message)
        => JsonSerializer.SerializeToElement(new { ok = false, error_code = code, message });

    private sealed class NativeTransfer(string id, DownloadRequest request)
    {
        public object Sync { get; } = new();
        public string Id { get; } = id;
        public DownloadRequest Request { get; } = request;
        public CancellationTokenSource? Cancellation { get; set; }
        public Task? Task { get; set; }
        public long DownloadedBytes { get; set; }
        public long? TotalBytes { get; set; } = request.ExpectedLength;
        public DownloadState State { get; set; } = DownloadState.Queued;
        public string? ErrorMessage { get; set; }

        public DesktopGoDownloadProjection ToProjection()
        {
            string fileName = Request.ResolveFileName();
            UriBuilder builder = new(Request.Source)
            {
                UserName = string.Empty,
                Password = string.Empty,
                Query = string.Empty,
                Fragment = string.Empty
            };
            return new DesktopGoDownloadProjection(
                Id,
                fileName,
                builder.Uri.AbsoluteUri,
                Path.Combine(Request.DestinationDirectory, fileName),
                DownloadedBytes,
                TotalBytes,
                0d,
                State.ToString(),
                DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
                ErrorMessage,
                string.IsNullOrWhiteSpace(Request.QueueId) ? "default" : Request.QueueId,
                Request.CategoryId,
                0,
                Math.Max(1, Request.ConnectionCount),
                Request.Priority.ToString(),
                DownloadBackendKind.Native.ToString(),
                null,
                Request.BackendPreference.ToString(),
                Request.AllowBackendFallback,
                false);
        }
    }
}
