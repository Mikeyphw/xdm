using System.Diagnostics;
using System.Net;
using System.Net.Http.Headers;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;

namespace XDM.Diagnostics;

public sealed class TransferHealthProbe : ITransferHealthProbe
{
    private const int MaximumDetailAddressCount = 4;
    private readonly HttpClient _httpClient;
    private readonly TransferHealthProbeOptions _options;
    private readonly object _sync = new();
    private TransferHealthProbeResult? _lastResult;

    public TransferHealthProbe(HttpClient httpClient)
        : this(httpClient, TransferHealthProbeOptions.Default)
    {
    }

    internal TransferHealthProbe(HttpClient httpClient, TransferHealthProbeOptions options)
    {
        _httpClient = httpClient;
        _options = options;
    }

    public event EventHandler? Changed;

    public TransferHealthProbeResult? LastResult
    {
        get
        {
            lock (_sync)
            {
                return _lastResult;
            }
        }
    }

    public async Task<TransferHealthProbeResult> ProbeAsync(
        Uri target,
        string destinationDirectory,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(target);
        ArgumentException.ThrowIfNullOrWhiteSpace(destinationDirectory);
        if (!target.IsAbsoluteUri || target.Scheme is not ("http" or "https"))
        {
            throw new ArgumentException("The live health probe requires an absolute HTTP or HTTPS URL.", nameof(target));
        }

        DateTimeOffset startedAt = DateTimeOffset.UtcNow;
        using CancellationTokenSource totalTimeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        totalTimeout.CancelAfter(_options.TotalTimeout);
        CancellationToken token = totalTimeout.Token;
        List<TransferHealthProbeStage> stages = [];

        stages.Add(await MeasureAsync("DNS", ProbeDnsAsync, token, cancellationToken).ConfigureAwait(false));
        stages.Add(await MeasureAsync("TCP", ProbeTcpAsync, token, cancellationToken).ConfigureAwait(false));
        stages.Add(target.Scheme == Uri.UriSchemeHttps
            ? await MeasureAsync("TLS", ProbeTlsAsync, token, cancellationToken).ConfigureAwait(false)
            : new TransferHealthProbeStage(
                "TLS",
                TransferHealthProbeStatus.Skipped,
                TimeSpan.Zero,
                "TLS is not used by this HTTP target.",
                EmptyDetails()));

        TransferHealthProbeStage httpStage = await MeasureAsync(
            "HTTP + range",
            ProbeHttpAsync,
            token,
            cancellationToken).ConfigureAwait(false);
        stages.Add(httpStage);
        stages.Add(await MeasureAsync(
            "Destination disk",
            ProbeDiskAsync,
            token,
            cancellationToken).ConfigureAwait(false));

        bool rangeSupported = httpStage.Details.TryGetValue("rangeSupported", out string? rangeValue)
            && bool.TryParse(rangeValue, out bool parsedRange)
            && parsedRange;
        long? diskRate = stages[^1].Details.TryGetValue("bytesPerSecond", out string? rateValue)
            && long.TryParse(rateValue, System.Globalization.NumberStyles.Integer, System.Globalization.CultureInfo.InvariantCulture, out long parsedRate)
                ? parsedRate
                : null;
        TransferHealthProbeResult result = new(
            target.GetLeftPart(UriPartial.Authority),
            startedAt,
            DateTimeOffset.UtcNow,
            stages,
            rangeSupported,
            diskRate);

        lock (_sync)
        {
            _lastResult = result;
        }

        Changed?.Invoke(this, EventArgs.Empty);
        return result;

        async Task<TransferHealthProbeStage> ProbeDnsAsync(CancellationToken stageToken)
        {
            IPAddress[] addresses = await Dns.GetHostAddressesAsync(target.DnsSafeHost, stageToken)
                .ConfigureAwait(false);
            if (addresses.Length == 0)
            {
                throw new SocketException((int)SocketError.HostNotFound);
            }

            return Passed(
                "DNS",
                $"Resolved {target.DnsSafeHost} to {addresses.Length} address{(addresses.Length == 1 ? string.Empty : "es")}.",
                new Dictionary<string, string?>(StringComparer.Ordinal)
                {
                    ["host"] = target.DnsSafeHost,
                    ["addresses"] = string.Join(", ", addresses.Take(MaximumDetailAddressCount).Select(static address => address.ToString()))
                });
        }

        async Task<TransferHealthProbeStage> ProbeTcpAsync(CancellationToken stageToken)
        {
            int port = ResolvePort(target);
            using TcpClient client = new();
            await client.ConnectAsync(target.DnsSafeHost, port, stageToken).ConfigureAwait(false);
            return Passed(
                "TCP",
                $"Connected to {target.DnsSafeHost}:{port}.",
                new Dictionary<string, string?>(StringComparer.Ordinal)
                {
                    ["remoteEndpoint"] = client.Client.RemoteEndPoint?.ToString(),
                    ["addressFamily"] = client.Client.AddressFamily.ToString()
                });
        }

        async Task<TransferHealthProbeStage> ProbeTlsAsync(CancellationToken stageToken)
        {
            int port = ResolvePort(target);
            using TcpClient client = new();
            await client.ConnectAsync(target.DnsSafeHost, port, stageToken).ConfigureAwait(false);
            await using SslStream stream = new(client.GetStream(), leaveInnerStreamOpen: false);
            await stream.AuthenticateAsClientAsync(
                new SslClientAuthenticationOptions
                {
                    TargetHost = target.DnsSafeHost,
                    EnabledSslProtocols = SslProtocols.None,
                    CertificateRevocationCheckMode = System.Security.Cryptography.X509Certificates.X509RevocationMode.Online
                },
                stageToken).ConfigureAwait(false);
            return Passed(
                "TLS",
                $"Negotiated {stream.SslProtocol} with normal certificate validation.",
                new Dictionary<string, string?>(StringComparer.Ordinal)
                {
                    ["protocol"] = stream.SslProtocol.ToString(),
                    ["cipher"] = stream.NegotiatedCipherSuite.ToString(),
                    ["certificateSubject"] = stream.RemoteCertificate?.Subject
                });
        }

        async Task<TransferHealthProbeStage> ProbeHttpAsync(CancellationToken stageToken)
        {
            using HttpRequestMessage request = new(HttpMethod.Get, target);
            request.Headers.Range = new RangeHeaderValue(0, 0);
            request.Headers.CacheControl = new CacheControlHeaderValue { NoCache = true };
            using HttpResponseMessage response = await _httpClient
                .SendAsync(request, HttpCompletionOption.ResponseHeadersRead, stageToken)
                .ConfigureAwait(false);

            bool rangeSupported = response.StatusCode == HttpStatusCode.PartialContent
                && response.Content.Headers.ContentRange is { From: 0, To: 0 };
            bool successful = response.IsSuccessStatusCode;
            TransferHealthProbeStatus status = !successful
                ? TransferHealthProbeStatus.Failed
                : rangeSupported
                    ? TransferHealthProbeStatus.Passed
                    : TransferHealthProbeStatus.Warning;
            string summary = !successful
                ? $"The bounded range request returned HTTP {(int)response.StatusCode} {response.ReasonPhrase}."
                : rangeSupported
                    ? "The endpoint returned a valid one-byte range response."
                    : $"HTTP succeeded, but resumable range behavior was not confirmed ({(int)response.StatusCode}).";
            return new TransferHealthProbeStage(
                "HTTP + range",
                status,
                TimeSpan.Zero,
                summary,
                new Dictionary<string, string?>(StringComparer.Ordinal)
                {
                    ["status"] = $"{(int)response.StatusCode} {response.ReasonPhrase}",
                    ["httpVersion"] = response.Version.ToString(),
                    ["rangeSupported"] = rangeSupported.ToString(System.Globalization.CultureInfo.InvariantCulture),
                    ["contentRange"] = response.Content.Headers.ContentRange?.ToString(),
                    ["contentLength"] = response.Content.Headers.ContentLength?.ToString(System.Globalization.CultureInfo.InvariantCulture),
                    ["etag"] = response.Headers.ETag?.ToString(),
                    ["lastModified"] = response.Content.Headers.LastModified?.ToString("O", System.Globalization.CultureInfo.InvariantCulture)
                });
        }

        async Task<TransferHealthProbeStage> ProbeDiskAsync(CancellationToken stageToken)
        {
            Directory.CreateDirectory(destinationDirectory);
            string testPath = Path.Combine(destinationDirectory, $".xdm-health-{Guid.NewGuid():N}.tmp");
            byte[] payload = new byte[_options.DiskWriteBytes];
            Stopwatch stopwatch = Stopwatch.StartNew();
            try
            {
                await using FileStream stream = new(
                    testPath,
                    FileMode.CreateNew,
                    FileAccess.Write,
                    FileShare.None,
                    64 * 1024,
                    FileOptions.Asynchronous | FileOptions.WriteThrough);
                await stream.WriteAsync(payload, stageToken).ConfigureAwait(false);
                await stream.FlushAsync(stageToken).ConfigureAwait(false);
                stream.Flush(flushToDisk: true);
            }
            finally
            {
                stopwatch.Stop();
                try
                {
                    File.Delete(testPath);
                }
                catch (IOException)
                {
                }
                catch (UnauthorizedAccessException)
                {
                }
            }

            double seconds = Math.Max(stopwatch.Elapsed.TotalSeconds, 0.001);
            long bytesPerSecond = (long)(_options.DiskWriteBytes / seconds);
            return Passed(
                "Destination disk",
                $"Wrote and flushed {_options.DiskWriteBytes / 1024:N0} KiB without retaining the test file.",
                new Dictionary<string, string?>(StringComparer.Ordinal)
                {
                    ["bytesWritten"] = _options.DiskWriteBytes.ToString(System.Globalization.CultureInfo.InvariantCulture),
                    ["bytesPerSecond"] = bytesPerSecond.ToString(System.Globalization.CultureInfo.InvariantCulture)
                });
        }
    }

    private async Task<TransferHealthProbeStage> MeasureAsync(
        string name,
        Func<CancellationToken, Task<TransferHealthProbeStage>> operation,
        CancellationToken boundedCancellationToken,
        CancellationToken callerCancellationToken)
    {
        Stopwatch stopwatch = Stopwatch.StartNew();
        using CancellationTokenSource stageTimeout = CancellationTokenSource.CreateLinkedTokenSource(boundedCancellationToken);
        stageTimeout.CancelAfter(_options.StageTimeout);
        try
        {
            TransferHealthProbeStage stage = await operation(stageTimeout.Token).ConfigureAwait(false);
            return stage with { Duration = stopwatch.Elapsed };
        }
        catch (OperationCanceledException) when (!callerCancellationToken.IsCancellationRequested)
        {
            string limit = boundedCancellationToken.IsCancellationRequested
                ? $"the {_options.TotalTimeout.TotalSeconds:0}-second total probe limit"
                : $"its {_options.StageTimeout.TotalSeconds:0}-second stage limit";
            return new TransferHealthProbeStage(
                name,
                TransferHealthProbeStatus.Failed,
                stopwatch.Elapsed,
                $"The {name} stage exceeded {limit}.",
                EmptyDetails());
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch (Exception exception) when (exception is SocketException
            or HttpRequestException
            or AuthenticationException
            or IOException
            or UnauthorizedAccessException)
        {
            return new TransferHealthProbeStage(
                name,
                TransferHealthProbeStatus.Failed,
                stopwatch.Elapsed,
                SecretRedactor.Redact(exception.Message),
                new Dictionary<string, string?>(StringComparer.Ordinal)
                {
                    ["exception"] = exception.GetType().Name
                });
        }
    }

    private static TransferHealthProbeStage Passed(
        string name,
        string summary,
        IReadOnlyDictionary<string, string?> details)
        => new(name, TransferHealthProbeStatus.Passed, TimeSpan.Zero, summary, details);

    private static Dictionary<string, string?> EmptyDetails()
        => new(StringComparer.Ordinal);

    private static int ResolvePort(Uri target)
        => target.IsDefaultPort
            ? target.Scheme == Uri.UriSchemeHttps ? 443 : 80
            : target.Port;
}
