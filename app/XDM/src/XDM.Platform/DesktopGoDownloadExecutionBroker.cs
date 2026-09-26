using System.Collections.Concurrent;
using System.Text.Json;
using XDM.DownloadEngine;

namespace XDM.Platform;

/// <summary>
/// Transitional XGO-79 physical execution adapter. Go owns admission intent and UI state;
/// this broker only materializes the explicitly requested legacy transfer side effect until XGO-80.
/// </summary>
public sealed class DesktopGoDownloadExecutionBroker
{
    private readonly IDownloadManager _downloads;
    private readonly ConcurrentDictionary<string, DownloadRequest> _pending = new(StringComparer.Ordinal);

    public DesktopGoDownloadExecutionBroker(IDownloadManager downloads)
        => _downloads = downloads ?? throw new ArgumentNullException(nameof(downloads));

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
        switch (action)
        {
            case "add":
            {
                string requestRef = payload.GetProperty("request_ref").GetString() ?? string.Empty;
                if (!_pending.TryRemove(requestRef, out DownloadRequest? request))
                {
                    return Reject("request_ref_missing", "The exact desktop download request is no longer available.");
                }
                string id = await _downloads.AddAsync(request, cancellationToken).ConfigureAwait(false);
                return JsonSerializer.SerializeToElement(new { ok = true, status = "created", download_id = id });
            }
            case "pause":
                await _downloads.PauseAsync(RequireDownloadId(payload), cancellationToken).ConfigureAwait(false);
                break;
            case "resume":
                await _downloads.ResumeAsync(RequireDownloadId(payload), cancellationToken).ConfigureAwait(false);
                break;
            case "cancel":
                await _downloads.CancelAsync(RequireDownloadId(payload), cancellationToken).ConfigureAwait(false);
                break;
            case "retry":
                await _downloads.RetryAsync(RequireDownloadId(payload), cancellationToken).ConfigureAwait(false);
                break;
            default:
                return Reject("unsupported_download_action", "The requested desktop download action is not allowlisted.");
        }
        return JsonSerializer.SerializeToElement(new { ok = true, status = "accepted" });
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
}
