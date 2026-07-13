using System.Diagnostics;
using System.Net;
using System.Security.Cryptography;

namespace XDM.Diagnostics;

public sealed class DeterministicDownloadTestService : IDeterministicDownloadTestService, IDisposable
{
    public const int ExpectedBytes = 1024 * 1024;
    public const string DefaultEndpoint = "https://speed.cloudflare.com/__down?bytes=1048576";

    private static readonly TimeSpan TestTimeout = TimeSpan.FromSeconds(30);
    private readonly HttpClient _httpClient;
    private readonly IDiagnosticEventStore _events;
    private readonly SemaphoreSlim _gate = new(1, 1);

    public DeterministicDownloadTestService(HttpClient httpClient, IDiagnosticEventStore events)
    {
        _httpClient = httpClient;
        _events = events;
    }

    public DeterministicDownloadTestResult? LastResult { get; private set; }

    public event EventHandler? Changed;

    public async Task<DeterministicDownloadTestResult> RunAsync(
        CancellationToken cancellationToken = default)
    {
        await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            Uri endpoint = ResolveEndpoint();
            DateTimeOffset startedAt = DateTimeOffset.UtcNow;
            Stopwatch stopwatch = Stopwatch.StartNew();
            using CancellationTokenSource timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            timeout.CancelAfter(TestTimeout);
            try
            {
                using HttpRequestMessage request = new(HttpMethod.Get, endpoint);
                request.Headers.UserAgent.ParseAdd("XDM-Modern-Diagnostics/1.0");
                using HttpResponseMessage response = await _httpClient
                    .SendAsync(request, HttpCompletionOption.ResponseHeadersRead, timeout.Token)
                    .ConfigureAwait(false);
                response.EnsureSuccessStatusCode();
                if (response.Content.Headers.ContentLength is long declaredLength
                    && declaredLength != ExpectedBytes)
                {
                    throw new InvalidDataException(
                        $"The diagnostics endpoint declared {declaredLength} bytes instead of {ExpectedBytes}.");
                }

                using IncrementalHash hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
                await using Stream source = await response.Content
                    .ReadAsStreamAsync(timeout.Token)
                    .ConfigureAwait(false);
                byte[] buffer = new byte[64 * 1024];
                long received = 0;
                while (true)
                {
                    int read = await source.ReadAsync(buffer, timeout.Token).ConfigureAwait(false);
                    if (read == 0)
                    {
                        break;
                    }

                    received = checked(received + read);
                    if (received > ExpectedBytes)
                    {
                        throw new InvalidDataException("The diagnostics endpoint exceeded the bounded 1 MiB response.");
                    }

                    hash.AppendData(buffer, 0, read);
                }

                if (received != ExpectedBytes)
                {
                    throw new EndOfStreamException(
                        $"The diagnostics endpoint returned {received} bytes; {ExpectedBytes} were expected.");
                }

                stopwatch.Stop();
                DeterministicDownloadTestResult result = new(
                    startedAt,
                    DateTimeOffset.UtcNow,
                    endpoint.GetLeftPart(UriPartial.Authority),
                    (int)response.StatusCode,
                    ExpectedBytes,
                    received,
                    Convert.ToHexString(hash.GetHashAndReset()).ToLowerInvariant(),
                    stopwatch.Elapsed,
                    received / Math.Max(stopwatch.Elapsed.TotalSeconds, 0.000_001),
                    true,
                    "The configured HTTP pipeline completed the bounded 1 MiB test download.");
                Publish(result);
                _events.Record(
                    DiagnosticSeverity.Information,
                    "XDM-DIAGNOSTICS-TEST-DOWNLOAD",
                    $"Bounded test download completed in {result.DurationText} at {result.SpeedText}.");
                return result;
            }
            catch (Exception exception) when (
                exception is HttpRequestException
                    or IOException
                    or InvalidDataException
                    or OperationCanceledException)
            {
                stopwatch.Stop();
                string message = exception is OperationCanceledException && !cancellationToken.IsCancellationRequested
                    ? "The bounded test download exceeded its 30-second deadline."
                    : SecretRedactor.Redact(exception.Message);
                DeterministicDownloadTestResult result = new(
                    startedAt,
                    DateTimeOffset.UtcNow,
                    endpoint.GetLeftPart(UriPartial.Authority),
                    exception is HttpRequestException { StatusCode: HttpStatusCode statusCode }
                        ? (int)statusCode
                        : 0,
                    ExpectedBytes,
                    0,
                    string.Empty,
                    stopwatch.Elapsed,
                    0,
                    false,
                    message);
                Publish(result);
                _events.Record(
                    DiagnosticSeverity.Warning,
                    "XDM-DIAGNOSTICS-TEST-DOWNLOAD",
                    message);
                if (exception is OperationCanceledException && cancellationToken.IsCancellationRequested)
                {
                    throw;
                }

                return result;
            }
        }
        finally
        {
            _gate.Release();
        }
    }

    public void Dispose()
    {
        _gate.Dispose();
        GC.SuppressFinalize(this);
    }

    private void Publish(DeterministicDownloadTestResult result)
    {
        LastResult = result;
        Changed?.Invoke(this, EventArgs.Empty);
    }

    private static Uri ResolveEndpoint()
    {
        string? configured = Environment.GetEnvironmentVariable("XDM_DIAGNOSTIC_TEST_URL");
        if (Uri.TryCreate(configured, UriKind.Absolute, out Uri? uri)
            && uri.Scheme == Uri.UriSchemeHttps)
        {
            return uri;
        }

        return new Uri(DefaultEndpoint, UriKind.Absolute);
    }
}
