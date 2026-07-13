using System.Collections.Concurrent;
using System.Globalization;
using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;

namespace XDM.DownloadEngine.Tests.FaultLab;

internal sealed class DeterministicHttpFaultServer : IDisposable, IAsyncDisposable
{
    private const int MaximumHeaderBytes = 64 * 1024;
    private readonly TcpListener _listener;
    private readonly CancellationTokenSource _shutdown = new();
    private readonly Func<FaultRequest, int, FaultResponse> _responseFactory;
    private readonly ConcurrentQueue<FaultRequest> _requests = new();
    private readonly Task _serverTask;
    private readonly X509Certificate2? _certificate;
    private readonly RSA? _certificateKey;
    private int _requestCount;

    public DeterministicHttpFaultServer(
        Func<FaultRequest, int, FaultResponse> responseFactory,
        bool useTls = false)
    {
        _responseFactory = responseFactory ?? throw new ArgumentNullException(nameof(responseFactory));
        if (useTls)
        {
            (_certificate, _certificateKey) = CreateSelfSignedCertificate();
        }

        _listener = new TcpListener(IPAddress.Loopback, 0);
        _listener.Start();
        int port = ((IPEndPoint)_listener.LocalEndpoint).Port;
        string scheme = useTls ? Uri.UriSchemeHttps : Uri.UriSchemeHttp;
        string host = IPAddress.Loopback.ToString();
        BaseUri = new Uri($"{scheme}://{host}:{port}/", UriKind.Absolute);
        _serverTask = RunAsync(_shutdown.Token);
    }

    public Uri BaseUri { get; }

    public int RequestCount => Volatile.Read(ref _requestCount);

    public IReadOnlyList<FaultRequest> Requests => _requests.ToArray();

    public Uri GetUri(string relativePath)
        => new(BaseUri, relativePath.TrimStart('/'));


    public void Dispose()
    {
        DisposeAsync().AsTask().GetAwaiter().GetResult();
        GC.SuppressFinalize(this);
    }

    public async ValueTask DisposeAsync()
    {
        if (!_shutdown.IsCancellationRequested)
        {
            _shutdown.Cancel();
        }

        _listener.Stop();
        try
        {
            await _serverTask.ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (_shutdown.IsCancellationRequested)
        {
        }
        catch (ObjectDisposedException) when (_shutdown.IsCancellationRequested)
        {
        }
        finally
        {
            _certificate?.Dispose();
            _certificateKey?.Dispose();
            _shutdown.Dispose();
            GC.SuppressFinalize(this);
        }
    }

    private async Task RunAsync(CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested)
        {
            TcpClient client;
            try
            {
                client = await _listener.AcceptTcpClientAsync(cancellationToken).ConfigureAwait(false);
            }
            catch (SocketException) when (cancellationToken.IsCancellationRequested)
            {
                break;
            }
            catch (ObjectDisposedException) when (cancellationToken.IsCancellationRequested)
            {
                break;
            }

            using (client)
            {
                try
                {
                    await HandleClientAsync(client, cancellationToken).ConfigureAwait(false);
                }
                catch (AuthenticationException)
                {
                    // Expected when a client rejects the protocol lab's self-signed certificate.
                }
                catch (IOException) when (!cancellationToken.IsCancellationRequested)
                {
                    // Fault scenarios intentionally allow peers to terminate malformed exchanges.
                }
            }
        }
    }

    private async Task HandleClientAsync(TcpClient client, CancellationToken cancellationToken)
    {
        await using NetworkStream networkStream = client.GetStream();
        if (_certificate is null)
        {
            await HandleStreamAsync(networkStream, cancellationToken).ConfigureAwait(false);
            return;
        }

        await using SslStream sslStream = new(networkStream, leaveInnerStreamOpen: true);
        await sslStream.AuthenticateAsServerAsync(
            new SslServerAuthenticationOptions
            {
                ServerCertificate = _certificate,
                ClientCertificateRequired = false
            },
            cancellationToken).ConfigureAwait(false);
        await HandleStreamAsync(sslStream, cancellationToken).ConfigureAwait(false);
    }

    private async Task HandleStreamAsync(Stream stream, CancellationToken cancellationToken)
    {
        FaultRequest? request = await ReadRequestAsync(stream, cancellationToken).ConfigureAwait(false);
        if (request is null)
        {
            return;
        }

        int attempt = Interlocked.Increment(ref _requestCount);
        _requests.Enqueue(request);
        FaultResponse response = _responseFactory(request, attempt);
        await WriteResponseAsync(stream, response, cancellationToken).ConfigureAwait(false);
    }

    private static async Task<FaultRequest?> ReadRequestAsync(
        Stream stream,
        CancellationToken cancellationToken)
    {
        using MemoryStream buffer = new();
        byte[] singleByte = new byte[1];
        int matched = 0;
        byte[] terminator = "\r\n\r\n"u8.ToArray();

        while (buffer.Length < MaximumHeaderBytes)
        {
            int read = await stream.ReadAsync(singleByte, cancellationToken).ConfigureAwait(false);
            if (read == 0)
            {
                return buffer.Length == 0
                    ? null
                    : throw new InvalidDataException("HTTP request headers ended prematurely.");
            }

            byte value = singleByte[0];
            buffer.WriteByte(value);
            if (value == terminator[matched])
            {
                matched++;
                if (matched == terminator.Length)
                {
                    break;
                }
            }
            else
            {
                matched = value == terminator[0] ? 1 : 0;
            }
        }

        if (matched != terminator.Length)
        {
            throw new InvalidDataException("HTTP request headers exceed the fault-lab limit.");
        }

        string headerText = Encoding.ASCII.GetString(buffer.ToArray());
        string[] lines = headerText.Split("\r\n", StringSplitOptions.None);
        string[] requestLine = lines[0].Split(' ', 3, StringSplitOptions.RemoveEmptyEntries);
        if (requestLine.Length != 3)
        {
            throw new InvalidDataException("The fault lab received an invalid HTTP request line.");
        }

        Dictionary<string, string> headers = new(StringComparer.OrdinalIgnoreCase);
        foreach (string line in lines.Skip(1))
        {
            if (line.Length == 0)
            {
                break;
            }

            int separator = line.IndexOf(':');
            if (separator <= 0)
            {
                throw new InvalidDataException("The fault lab received an invalid HTTP header.");
            }

            headers[line[..separator].Trim()] = line[(separator + 1)..].Trim();
        }

        return new FaultRequest(requestLine[0], requestLine[1], requestLine[2], headers);
    }

    private static async Task WriteResponseAsync(
        Stream stream,
        FaultResponse response,
        CancellationToken cancellationToken)
    {
        if (response.AbortBeforeHeaders)
        {
            return;
        }

        if (response.DelayBeforeHeaders > TimeSpan.Zero)
        {
            await Task.Delay(response.DelayBeforeHeaders, cancellationToken).ConfigureAwait(false);
        }

        int bodyBytesToWrite = Math.Clamp(
            response.BodyBytesToWrite ?? response.Body.Length,
            0,
            response.Body.Length);
        long declaredLength = response.DeclaredContentLength ?? response.Body.Length;
        StringBuilder headers = new();
        headers.Append("HTTP/1.1 ")
            .Append(response.StatusCode)
            .Append(' ')
            .Append(response.ReasonPhrase ?? GetReasonPhrase(response.StatusCode))
            .Append("\r\n");

        bool hasContentLength = false;
        bool hasTransferEncoding = false;
        foreach ((string name, string value) in response.Headers)
        {
            hasContentLength |= string.Equals(name, "Content-Length", StringComparison.OrdinalIgnoreCase);
            hasTransferEncoding |= string.Equals(name, "Transfer-Encoding", StringComparison.OrdinalIgnoreCase);
            headers.Append(name).Append(": ").Append(value).Append("\r\n");
        }

        if (response.UseChunkedEncoding && !hasTransferEncoding)
        {
            headers.Append("Transfer-Encoding: chunked\r\n");
        }
        else if (!response.OmitContentLength && !hasContentLength && !hasTransferEncoding)
        {
            headers.Append("Content-Length: ")
                .Append(declaredLength.ToString(CultureInfo.InvariantCulture))
                .Append("\r\n");
        }

        foreach (string rawHeaderLine in response.RawHeaderLines)
        {
            headers.Append(rawHeaderLine).Append("\r\n");
        }

        headers.Append("Connection: close\r\n\r\n");
        byte[] headerBytes = Encoding.ASCII.GetBytes(headers.ToString());
        await stream.WriteAsync(headerBytes, cancellationToken).ConfigureAwait(false);

        if (response.AbortAfterHeaders)
        {
            await stream.FlushAsync(cancellationToken).ConfigureAwait(false);
            return;
        }

        if (response.UseChunkedEncoding)
        {
            await WriteChunkedBodyAsync(
                stream,
                response.Body.AsMemory(0, bodyBytesToWrite),
                response.ChunkSize,
                response.DelayBetweenChunks,
                response.OmitChunkTerminator,
                cancellationToken).ConfigureAwait(false);
        }
        else if (bodyBytesToWrite > 0)
        {
            await stream.WriteAsync(response.Body.AsMemory(0, bodyBytesToWrite), cancellationToken)
                .ConfigureAwait(false);
        }

        await stream.FlushAsync(cancellationToken).ConfigureAwait(false);
    }

    private static async Task WriteChunkedBodyAsync(
        Stream stream,
        ReadOnlyMemory<byte> body,
        int configuredChunkSize,
        TimeSpan delayBetweenChunks,
        bool omitTerminator,
        CancellationToken cancellationToken)
    {
        int chunkSize = configuredChunkSize > 0 ? configuredChunkSize : Math.Max(body.Length, 1);
        int offset = 0;
        while (offset < body.Length)
        {
            int count = Math.Min(chunkSize, body.Length - offset);
            byte[] prefix = Encoding.ASCII.GetBytes(count.ToString("X", CultureInfo.InvariantCulture) + "\r\n");
            await stream.WriteAsync(prefix, cancellationToken).ConfigureAwait(false);
            await stream.WriteAsync(body.Slice(offset, count), cancellationToken).ConfigureAwait(false);
            await stream.WriteAsync("\r\n"u8.ToArray(), cancellationToken).ConfigureAwait(false);
            offset += count;
            if (delayBetweenChunks > TimeSpan.Zero && offset < body.Length)
            {
                await stream.FlushAsync(cancellationToken).ConfigureAwait(false);
                await Task.Delay(delayBetweenChunks, cancellationToken).ConfigureAwait(false);
            }
        }

        if (!omitTerminator)
        {
            await stream.WriteAsync("0\r\n\r\n"u8.ToArray(), cancellationToken).ConfigureAwait(false);
        }
    }

    private static (X509Certificate2 Certificate, RSA Key) CreateSelfSignedCertificate()
    {
        RSA key = RSA.Create(2048);
        CertificateRequest request = new(
            "CN=localhost",
            key,
            HashAlgorithmName.SHA256,
            RSASignaturePadding.Pkcs1);
        request.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, false));
        request.CertificateExtensions.Add(new X509KeyUsageExtension(X509KeyUsageFlags.DigitalSignature, false));
        SubjectAlternativeNameBuilder alternativeNames = new();
        alternativeNames.AddDnsName("localhost");
        alternativeNames.AddIpAddress(IPAddress.Loopback);
        request.CertificateExtensions.Add(alternativeNames.Build());
        try
        {
            X509Certificate2 certificate = request.CreateSelfSigned(
                DateTimeOffset.UtcNow.AddMinutes(-5),
                DateTimeOffset.UtcNow.AddHours(1));
            return (certificate, key);
        }
        catch (CryptographicException)
        {
            key.Dispose();
            throw;
        }
    }

    private static string GetReasonPhrase(int statusCode)
        => statusCode switch
        {
            200 => "OK",
            206 => "Partial Content",
            302 => "Found",
            401 => "Unauthorized",
            403 => "Forbidden",
            404 => "Not Found",
            407 => "Proxy Authentication Required",
            416 => "Range Not Satisfiable",
            429 => "Too Many Requests",
            500 => "Internal Server Error",
            502 => "Bad Gateway",
            503 => "Service Unavailable",
            _ => "Fault Lab Response"
        };
}

internal sealed record FaultRequest(
    string Method,
    string Target,
    string Protocol,
    IReadOnlyDictionary<string, string> Headers)
{
    public long? RangeStart
    {
        get
        {
            if (!Headers.TryGetValue("Range", out string? value)
                || !value.StartsWith("bytes=", StringComparison.OrdinalIgnoreCase))
            {
                return null;
            }

            string start = value[6..].Split('-', 2)[0];
            return long.TryParse(
                start,
                NumberStyles.None,
                CultureInfo.InvariantCulture,
                out long parsed)
                    ? parsed
                    : null;
        }
    }

    public string? IfRange
        => Headers.TryGetValue("If-Range", out string? value) ? value : null;

    public string? Authorization
        => Headers.TryGetValue("Authorization", out string? value) ? value : null;
}

internal sealed record FaultResponse(
    int StatusCode,
    byte[] Body,
    IReadOnlyDictionary<string, string> Headers,
    long? DeclaredContentLength = null,
    int? BodyBytesToWrite = null,
    bool UseChunkedEncoding = false,
    int ChunkSize = 0,
    TimeSpan DelayBeforeHeaders = default,
    TimeSpan DelayBetweenChunks = default,
    bool OmitContentLength = false,
    bool OmitChunkTerminator = false,
    bool AbortBeforeHeaders = false,
    bool AbortAfterHeaders = false,
    string? ReasonPhrase = null,
    string[]? AdditionalRawHeaderLines = null)
{
    public IReadOnlyList<string> RawHeaderLines => AdditionalRawHeaderLines ?? [];

    public static FaultResponse Ok(
        byte[] body,
        string? entityTag = null,
        long? declaredContentLength = null,
        int? bodyBytesToWrite = null)
        => new(
            200,
            body,
            CreateHeaders(entityTag),
            declaredContentLength,
            bodyBytesToWrite);

    public static FaultResponse Partial(
        byte[] body,
        long start,
        long end,
        long totalLength,
        string? entityTag = null,
        long? declaredContentLength = null,
        int? bodyBytesToWrite = null)
    {
        Dictionary<string, string> headers = CreateHeaders(entityTag);
        headers["Content-Range"] = $"bytes {start}-{end}/{totalLength}";
        return new FaultResponse(
            206,
            body,
            headers,
            declaredContentLength,
            bodyBytesToWrite);
    }

    public static FaultResponse RangeNotSatisfiable(long totalLength, string? entityTag = null)
    {
        Dictionary<string, string> headers = CreateHeaders(entityTag);
        headers["Content-Range"] = $"bytes */{totalLength}";
        return new FaultResponse(416, [], headers, 0, 0);
    }

    public static FaultResponse Redirect(string location)
        => new(
            302,
            [],
            new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase)
            {
                ["Location"] = location
            },
            0,
            0);

    public static FaultResponse Text(string content, string contentType)
        => new(
            200,
            Encoding.UTF8.GetBytes(content),
            new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase)
            {
                ["Content-Type"] = contentType
            });

    private static Dictionary<string, string> CreateHeaders(string? entityTag)
    {
        Dictionary<string, string> headers = new(StringComparer.OrdinalIgnoreCase);
        if (!string.IsNullOrWhiteSpace(entityTag))
        {
            headers["ETag"] = entityTag;
        }

        return headers;
    }
}
