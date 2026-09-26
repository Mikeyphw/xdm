using System.Net.Http;
using System.Net.NetworkInformation;
using System.Text.Json;
using XDM.Core.Settings;

namespace XDM.Platform;

public sealed record DesktopWakeupRegistration(string WakeId, long DeadlineMs, string? Reason);

public sealed class DesktopGoPlatformDispatcher : IDisposable
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web)
    {
        PropertyNamingPolicy = JsonNamingPolicy.SnakeCaseLower,
        DictionaryKeyPolicy = JsonNamingPolicy.SnakeCaseLower
    };
    private readonly ISettingsService _settings;
    private readonly DesktopGoPublicationBroker _publication;
    private static readonly TimeSpan MaxTimerSlice = TimeSpan.FromDays(24);
    private readonly Dictionary<string, Timer> _wakeups = new(StringComparer.Ordinal);
    private readonly object _wakeGate = new();
    private bool _disposed;

    public DesktopGoPlatformDispatcher(ISettingsService settings, DesktopGoPublicationBroker publication)
    {
        _settings = settings ?? throw new ArgumentNullException(nameof(settings));
        _publication = publication ?? throw new ArgumentNullException(nameof(publication));
    }

    public event EventHandler<DesktopWakeupRegistration>? WakeDue;

    public async Task<JsonElement> DispatchAsync(JsonElement request, CancellationToken cancellationToken = default)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        string kind = request.GetProperty("kind").GetString() ?? string.Empty;
        JsonElement payload = request.TryGetProperty("payload", out JsonElement body) ? body : default;
        return kind switch
        {
            "publication" => JsonSerializer.SerializeToElement(
                await _publication.CommitAsync(payload.Deserialize<DesktopPublicationRequest>(JsonOptions)
                    ?? throw new InvalidDataException("Missing desktop publication payload."), cancellationToken).ConfigureAwait(false), JsonOptions),
            "system_proxy" => ResolveProxy(payload),
            "secret_lookup" => ResolveSecret(payload),
            "wakeup" => RegisterWakeup(payload),
            "runtime_conditions" => JsonSerializer.SerializeToElement(new
            {
                network_available = NetworkInterface.GetIsNetworkAvailable(),
                source = "desktop-host"
            }),
            _ => throw new NotSupportedException($"Unsupported Go desktop platform request: {kind}")
        };
    }

    private JsonElement ResolveProxy(JsonElement payload)
    {
        ProxySettings configured = (_settings.Current.Network?.Proxy ?? ProxySettings.SystemDefault).Normalize();
        string? targetUrl = payload.ValueKind == JsonValueKind.Object && payload.TryGetProperty("target_url", out JsonElement target)
            ? target.GetString()
            : null;
        string? systemProxy = null;
        if (configured.Mode == ProxyMode.System && Uri.TryCreate(targetUrl, UriKind.Absolute, out Uri? targetUri))
        {
            Uri? proxy = HttpClient.DefaultProxy.GetProxy(targetUri);
            if (proxy is not null && proxy != targetUri)
            {
                systemProxy = proxy.AbsoluteUri;
            }
        }
        string? manualProxy = configured.Mode == ProxyMode.Manual && !string.IsNullOrWhiteSpace(configured.Host)
            ? new UriBuilder(Uri.UriSchemeHttp, configured.Host, configured.Port).Uri.AbsoluteUri
            : null;
        return JsonSerializer.SerializeToElement(new
        {
            mode = configured.Mode.ToString().ToLowerInvariant(),
            proxy_uri = manualProxy ?? systemProxy,
            pac_url = configured.Mode == ProxyMode.AutomaticScript ? configured.AutomaticConfigurationUrl : null,
            bypass_local = configured.BypassLocal,
            bypass_list = configured.BypassList ?? []
        });
    }

    private JsonElement ResolveSecret(JsonElement payload)
    {
        JsonElement secretRef = payload.GetProperty("secret_ref");
        string id = secretRef.GetProperty("id").GetString() ?? string.Empty;
        string scope = secretRef.GetProperty("scope").GetString() ?? string.Empty;
        string? value = null;

        ProxySettings proxy = (_settings.Current.Network?.Proxy ?? ProxySettings.SystemDefault).Normalize();
        if (id.Equals("proxy-password", StringComparison.Ordinal) && !string.IsNullOrWhiteSpace(proxy.Password))
        {
            value = proxy.Password;
        }
        else if (Uri.TryCreate(scope, UriKind.Absolute, out Uri? uri))
        {
            value = ServerCredentialResolver.Resolve(_settings.Current, uri).Password;
        }
        if (value is null)
        {
            throw new KeyNotFoundException("Secret reference was not resolved by the desktop credential source.");
        }
        return JsonSerializer.SerializeToElement(new { value });
    }

    private JsonElement RegisterWakeup(JsonElement payload)
    {
        DesktopWakeupRegistration registration = new(
            payload.GetProperty("wake_id").GetString() ?? throw new InvalidDataException("Missing wake_id."),
            payload.GetProperty("deadline_ms").GetInt64(),
            payload.TryGetProperty("reason", out JsonElement reason) ? reason.GetString() : null);
        lock (_wakeGate)
        {
            if (_wakeups.Remove(registration.WakeId, out Timer? existing))
            {
                existing.Dispose();
            }
            ScheduleWakeupLocked(registration);
        }
        return JsonSerializer.SerializeToElement(new { registered = true, wake_id = registration.WakeId, deadline_ms = registration.DeadlineMs });
    }

    private void ScheduleWakeupLocked(DesktopWakeupRegistration registration)
    {
        TimeSpan remaining = DateTimeOffset.FromUnixTimeMilliseconds(registration.DeadlineMs) - DateTimeOffset.UtcNow;
        TimeSpan delay = remaining <= TimeSpan.Zero ? TimeSpan.Zero : remaining;
        if (delay > MaxTimerSlice)
        {
            delay = MaxTimerSlice;
        }
        _wakeups[registration.WakeId] = new Timer(_ => OnWakeTimer(registration), null, delay, Timeout.InfiniteTimeSpan);
    }

    private void OnWakeTimer(DesktopWakeupRegistration registration)
    {
        lock (_wakeGate)
        {
            if (_wakeups.Remove(registration.WakeId, out Timer? timer))
            {
                timer.Dispose();
            }
            TimeSpan remaining = DateTimeOffset.FromUnixTimeMilliseconds(registration.DeadlineMs) - DateTimeOffset.UtcNow;
            if (remaining > TimeSpan.Zero)
            {
                ScheduleWakeupLocked(registration);
                return;
            }
        }
        WakeDue?.Invoke(this, registration);
    }

    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }
        _disposed = true;
        lock (_wakeGate)
        {
            foreach (Timer timer in _wakeups.Values)
            {
                timer.Dispose();
            }
            _wakeups.Clear();
        }
        WakeDue = null;
        GC.SuppressFinalize(this);
    }
}
