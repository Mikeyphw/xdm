using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace XDM.BrowserIntegration;

public sealed class LoopbackBrowserIntegrationService : IBrowserIntegrationService
{
    private static readonly JsonSerializerOptions SerializerOptions = new(JsonSerializerDefaults.Web);
    private static readonly TimeSpan CaptureDecisionTimeout = TimeSpan.FromSeconds(20);
    private readonly object _sync = new();
    private readonly SemaphoreSlim _lifecycleGate = new(1, 1);
    private readonly int _port;
    private HttpListener? _listener;
    private CancellationTokenSource? _lifetimeCancellation;
    private Task? _listenerTask;
    private BrowserIntegrationStatus _current;
    private bool _disposed;

    public LoopbackBrowserIntegrationService()
        : this(9614, LoadOrCreateToken())
    {
    }

    public LoopbackBrowserIntegrationService(int port, string authenticationToken)
    {
        ArgumentOutOfRangeException.ThrowIfLessThan(port, 1);
        ArgumentOutOfRangeException.ThrowIfGreaterThan(port, 65535);
        ArgumentException.ThrowIfNullOrWhiteSpace(authenticationToken);
        _port = port;
        _current = new BrowserIntegrationStatus(
            false,
            port,
            BrowserCaptureProtocol.ProtocolVersion,
            authenticationToken);
    }

    public event EventHandler<BrowserCaptureEventArgs>? CaptureReceived;

    public event EventHandler<BrowserStatusChangedEventArgs>? StatusChanged;

    public BrowserIntegrationStatus Current
    {
        get
        {
            lock (_sync)
            {
                return _current;
            }
        }
    }

    public async Task InitializeAsync(CancellationToken cancellationToken = default)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        await _lifecycleGate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            if (_listenerTask is { IsCompleted: false })
            {
                return;
            }

            HttpListener listener = new();
            listener.Prefixes.Add($"http://127.0.0.1:{_port}/");
            CancellationTokenSource lifetimeCancellation = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            try
            {
                listener.Start();
            }
            catch (HttpListenerException exception)
            {
                listener.Close();
                lifetimeCancellation.Dispose();
                UpdateStatus(status => status with
                {
                    IsListening = false,
                    LastError = exception.Message
                });
                return;
            }
            catch (PlatformNotSupportedException exception)
            {
                listener.Close();
                lifetimeCancellation.Dispose();
                UpdateStatus(status => status with
                {
                    IsListening = false,
                    LastError = exception.Message
                });
                return;
            }

            _listener = listener;
            _lifetimeCancellation = lifetimeCancellation;
            UpdateStatus(status => status with
            {
                IsListening = true,
                StartedAt = DateTimeOffset.UtcNow,
                LastError = null
            });
            _listenerTask = ListenAsync(listener, lifetimeCancellation.Token);
        }
        finally
        {
            _lifecycleGate.Release();
        }
    }

    public async Task StopAsync(CancellationToken cancellationToken = default)
    {
        await _lifecycleGate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            if (_lifetimeCancellation is not null)
            {
                await _lifetimeCancellation.CancelAsync().ConfigureAwait(false);
            }

            _listener?.Close();
            if (_listenerTask is not null)
            {
                try
                {
                    await _listenerTask.ConfigureAwait(false);
                }
                catch (OperationCanceledException)
                {
                }
                catch (HttpListenerException)
                {
                }
            }

            _listener = null;
            _listenerTask = null;
            _lifetimeCancellation?.Dispose();
            _lifetimeCancellation = null;
            UpdateStatus(status => status with { IsListening = false });
        }
        finally
        {
            _lifecycleGate.Release();
        }
    }

    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }

        StopAsync().GetAwaiter().GetResult();
        _disposed = true;
        _lifecycleGate.Dispose();
        GC.SuppressFinalize(this);
    }

    private async Task ListenAsync(HttpListener listener, CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested)
        {
            HttpListenerContext context;
            try
            {
                context = await listener.GetContextAsync().WaitAsync(cancellationToken).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (HttpListenerException) when (cancellationToken.IsCancellationRequested)
            {
                break;
            }

            _ = HandleContextSafelyAsync(context, cancellationToken);
        }
    }

    private async Task HandleContextSafelyAsync(HttpListenerContext context, CancellationToken cancellationToken)
    {
        try
        {
            await HandleContextAsync(context, cancellationToken).ConfigureAwait(false);
        }
        catch (JsonException exception)
        {
            UpdateStatus(status => status with { LastError = exception.Message });
            await WriteJsonAsync(context.Response, HttpStatusCode.BadRequest, new { error = "invalid_json" }, cancellationToken)
                .ConfigureAwait(false);
        }
        catch (InvalidDataException exception)
        {
            UpdateStatus(status => status with { LastError = exception.Message });
            await WriteJsonAsync(context.Response, HttpStatusCode.BadRequest, new { error = exception.Message }, cancellationToken)
                .ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
        }
        catch (IOException exception)
        {
            UpdateStatus(status => status with { LastError = exception.Message });
            context.Response.StatusCode = (int)HttpStatusCode.InternalServerError;
        }
        finally
        {
            context.Response.Close();
        }
    }

    private async Task HandleContextAsync(HttpListenerContext context, CancellationToken cancellationToken)
    {
        string path = context.Request.Url?.AbsolutePath ?? "/";
        bool protectedEndpoint = string.Equals(path, "/health", StringComparison.Ordinal)
            || string.Equals(path, "/capture", StringComparison.Ordinal);
        if (protectedEndpoint && !IsAuthenticated(context.Request))
        {
            await WriteJsonAsync(context.Response, HttpStatusCode.Unauthorized, new { error = "unauthorized" }, cancellationToken)
                .ConfigureAwait(false);
            return;
        }

        if (context.Request.HttpMethod == "GET" && string.Equals(path, "/health", StringComparison.Ordinal))
        {
            BrowserIntegrationStatus status = Current;
            await WriteJsonAsync(context.Response, HttpStatusCode.OK, new
            {
                status.IsListening,
                status.Port,
                status.ProtocolVersion,
                status.StartedAt,
                status.LastMessageAt,
                status.LastBrowser,
                status.LastCapturedUrl,
                status.LastError
            }, cancellationToken).ConfigureAwait(false);
            return;
        }

        if (context.Request.HttpMethod != "POST" || !string.Equals(path, "/capture", StringComparison.Ordinal))
        {
            await WriteJsonAsync(context.Response, HttpStatusCode.NotFound, new { error = "not_found" }, cancellationToken)
                .ConfigureAwait(false);
            return;
        }

        if (context.Request.ContentLength64 is <= 0 or > BrowserCaptureProtocol.MaximumPayloadBytes)
        {
            throw new InvalidDataException("Browser capture payload is empty or exceeds 128 KiB.");
        }

        byte[] payload = new byte[checked((int)context.Request.ContentLength64)];
        await context.Request.InputStream.ReadExactlyAsync(payload, cancellationToken).ConfigureAwait(false);
        BrowserCaptureRequest request = BrowserCaptureProtocol.Parse(payload);

        BrowserCaptureEventArgs captureEvent = new(request);
        EventHandler<BrowserCaptureEventArgs>? handlers = CaptureReceived;
        if (handlers is null)
        {
            await WriteJsonAsync(
                context.Response,
                HttpStatusCode.ServiceUnavailable,
                new BrowserCaptureAcknowledgement(request.RequestId, false, "capture_handler_unavailable"),
                cancellationToken).ConfigureAwait(false);
            return;
        }

        UpdateStatus(status => status with
        {
            LastMessageAt = DateTimeOffset.UtcNow,
            LastBrowser = request.Browser,
            LastCapturedUrl = request.Url.GetLeftPart(UriPartial.Path),
            LastError = null
        });

        handlers.Invoke(this, captureEvent);
        BrowserCaptureDecision decision;
        try
        {
            decision = await captureEvent
                .WaitForDecisionAsync(CaptureDecisionTimeout, cancellationToken)
                .ConfigureAwait(false);
        }
        catch (TimeoutException)
        {
            decision = BrowserCaptureDecision.Reject("capture_acknowledgement_timeout");
        }

        HttpStatusCode statusCode = decision.Accepted
            ? HttpStatusCode.Accepted
            : HttpStatusCode.Conflict;
        await WriteJsonAsync(
            context.Response,
            statusCode,
            new BrowserCaptureAcknowledgement(
                request.RequestId,
                decision.Accepted,
                decision.Reason,
                decision.DownloadId),
            cancellationToken).ConfigureAwait(false);
    }

    private bool IsAuthenticated(HttpListenerRequest request)
    {
        byte[] supplied = Encoding.UTF8.GetBytes(request.Headers["X-XDM-Token"] ?? string.Empty);
        byte[] expected = Encoding.UTF8.GetBytes(Current.AuthenticationToken);
        return supplied.Length == expected.Length
            && CryptographicOperations.FixedTimeEquals(supplied, expected);
    }

    private static async Task WriteJsonAsync(
        HttpListenerResponse response,
        HttpStatusCode statusCode,
        object value,
        CancellationToken cancellationToken)
    {
        response.StatusCode = (int)statusCode;
        response.ContentType = "application/json; charset=utf-8";
        await JsonSerializer.SerializeAsync(response.OutputStream, value, SerializerOptions, cancellationToken)
            .ConfigureAwait(false);
    }

    private void UpdateStatus(Func<BrowserIntegrationStatus, BrowserIntegrationStatus> update)
    {
        BrowserIntegrationStatus next;
        lock (_sync)
        {
            next = update(_current);
            _current = next;
        }

        StatusChanged?.Invoke(this, new BrowserStatusChangedEventArgs(next));
    }

    private static string LoadOrCreateToken()
    {
        string directory = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "xdm-modern");
        string path = Path.Combine(directory, "browser-token.txt");
        try
        {
            if (File.Exists(path))
            {
                string existing = File.ReadAllText(path).Trim();
                if (existing.Length == 64
                    && existing.All(static character => char.IsAsciiHexDigit(character)))
                {
                    return existing;
                }
            }

            Directory.CreateDirectory(directory);
            string token = Convert.ToHexString(RandomNumberGenerator.GetBytes(32));
            File.WriteAllText(path, token);
            return token;
        }
        catch (IOException)
        {
            return Convert.ToHexString(RandomNumberGenerator.GetBytes(32));
        }
        catch (UnauthorizedAccessException)
        {
            return Convert.ToHexString(RandomNumberGenerator.GetBytes(32));
        }
    }
}
