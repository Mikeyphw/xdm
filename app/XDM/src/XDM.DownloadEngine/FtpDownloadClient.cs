using System.Globalization;
using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;

namespace XDM.DownloadEngine;

public sealed class FtpDownloadClient : IFtpDownloadClient
{
    private const int BufferSize = 64 * 1024;
    private static readonly TimeSpan ProgressInterval = TimeSpan.FromMilliseconds(150);
    private static readonly string[] MdtmFormats = ["yyyyMMddHHmmss", "yyyyMMddHHmmss.FFF"];

    public Task<FtpDownloadResult> DownloadAsync(
        Uri source,
        string destinationPath,
        long resumeOffset,
        string? username,
        string? password,
        Func<long, long?, ValueTask> progress,
        CancellationToken cancellationToken = default)
        => DownloadAsync(
            source,
            destinationPath,
            resumeOffset,
            username,
            password,
            progress,
            new FtpTransferContext(),
            cancellationToken);

    public async Task<FtpDownloadResult> DownloadAsync(
        Uri source,
        string destinationPath,
        long resumeOffset,
        string? username,
        string? password,
        Func<long, long?, ValueTask> progress,
        FtpTransferContext transferContext,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(source);
        ArgumentException.ThrowIfNullOrWhiteSpace(destinationPath);
        ArgumentNullException.ThrowIfNull(progress);
        ArgumentNullException.ThrowIfNull(transferContext);
        ArgumentOutOfRangeException.ThrowIfNegative(resumeOffset);
        if (!source.IsAbsoluteUri || source.Scheme is not ("ftp" or "ftps"))
        {
            throw new ArgumentException("An absolute FTP or FTPS URL is required.", nameof(source));
        }

        string remotePath = Uri.UnescapeDataString(source.AbsolutePath);
        ValidateCommandValue(remotePath, nameof(source));
        (string resolvedUsername, string resolvedPassword) = ResolveCredentials(source, username, password);
        ValidateCommandValue(resolvedUsername, nameof(username));
        ValidateCommandValue(resolvedPassword, nameof(password));

        int port = source.Port > 0 ? source.Port : 21;
        bool useTls = source.Scheme == "ftps";
        bool implicitTls = useTls && port == 990;

        using TcpClient controlClient = await ConnectTcpClientAsync(
            source.Host,
            port,
            preferredAddressFamily: null,
            timeout: transferContext.ConnectTimeout,
            cancellationToken: cancellationToken).ConfigureAwait(false);
        Stream controlStream = controlClient.GetStream();
        if (implicitTls)
        {
            controlStream = await AuthenticateTlsAsync(
                controlStream, source.Host, transferContext.ConnectTimeout, cancellationToken)
                .ConfigureAwait(false);
        }

        await using FtpControlConnection control = new(controlStream, transferContext.RequestTimeout);
        await control.ExpectAsync(220, cancellationToken).ConfigureAwait(false);
        if (useTls && !implicitTls)
        {
            await control.CommandAsync("AUTH TLS", 234, cancellationToken).ConfigureAwait(false);
            controlStream = await AuthenticateTlsAsync(
                control.DetachStream(), source.Host, transferContext.ConnectTimeout, cancellationToken)
                .ConfigureAwait(false);
            control.AttachStream(controlStream);
        }

        FtpResponse userResponse = await control.CommandAsync(
            $"USER {resolvedUsername}",
            230,
            331,
            cancellationToken).ConfigureAwait(false);
        if (userResponse.Code == 331)
        {
            await control.CommandAsync($"PASS {resolvedPassword}", 230, cancellationToken)
                .ConfigureAwait(false);
        }

        if (useTls)
        {
            await control.CommandAsync("PBSZ 0", 200, cancellationToken).ConfigureAwait(false);
            await control.CommandAsync("PROT P", 200, cancellationToken).ConfigureAwait(false);
        }

        await control.CommandAsync("TYPE I", 200, cancellationToken).ConfigureAwait(false);
        long? totalBytes = await TryGetSizeAsync(control, remotePath, cancellationToken).ConfigureAwait(false);
        DateTimeOffset? lastModified = await TryGetLastModifiedAsync(control, remotePath, cancellationToken)
            .ConfigureAwait(false);

        if (transferContext.ExpectedLength is long expectedLength
            && totalBytes is long actualLength
            && expectedLength != actualLength)
        {
            throw new DownloadIntegrityException(
                $"The remote FTP file length changed. Expected {expectedLength}; received {actualLength}.");
        }

        if (resumeOffset > 0)
        {
            if (transferContext.ExpectedLastModified is not DateTimeOffset expectedLastModified)
            {
                throw new DownloadIntegrityException(
                    "FTP resume requires a previously recorded MDTM value; existing bytes cannot be safely appended without remote identity proof.");
            }
            if (lastModified is not DateTimeOffset actualLastModified)
            {
                throw new DownloadIntegrityException(
                    "The FTP server omitted MDTM while a validated resume was required.");
            }
            if (expectedLastModified != actualLastModified)
            {
                throw new DownloadIntegrityException(
                    "The remote FTP file modification time changed while resuming.");
            }
        }

        long effectiveOffset = resumeOffset;
        if (totalBytes is long knownSize && effectiveOffset > knownSize)
        {
            effectiveOffset = 0;
        }

        if (effectiveOffset > 0)
        {
            FtpResponse restart = await control.CommandAsync(
                $"REST {effectiveOffset.ToString(CultureInfo.InvariantCulture)}",
                350,
                500,
                501,
                502,
                504,
                cancellationToken).ConfigureAwait(false);
            if (restart.Code != 350)
            {
                effectiveOffset = 0;
            }
        }

        (string dataHost, int dataPort) = await OpenPassiveEndpointAsync(
            control,
            source.Host,
            addressFamily: controlClient.Client.AddressFamily,
            cancellationToken: cancellationToken).ConfigureAwait(false);
        using TcpClient dataClient = await ConnectTcpClientAsync(
            dataHost,
            dataPort,
            preferredAddressFamily: controlClient.Client.AddressFamily,
            timeout: transferContext.ConnectTimeout,
            cancellationToken: cancellationToken).ConfigureAwait(false);
        Stream dataStream = dataClient.GetStream();
        if (useTls)
        {
            dataStream = await AuthenticateTlsAsync(
                dataStream, source.Host, transferContext.ConnectTimeout, cancellationToken)
                .ConfigureAwait(false);
        }

        await using Stream ownedDataStream = dataStream;
        await control.CommandAsync($"RETR {remotePath}", 125, 150, cancellationToken)
            .ConfigureAwait(false);

        Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(destinationPath))!);
        FileMode mode = effectiveOffset > 0 ? FileMode.Append : FileMode.Create;
        long downloaded = effectiveOffset;
        DateTimeOffset lastProgress = DateTimeOffset.MinValue;
        await using (FileStream destination = new(
            destinationPath,
            mode,
            FileAccess.Write,
            FileShare.Read,
            BufferSize,
            FileOptions.Asynchronous | FileOptions.SequentialScan))
        {
            byte[] buffer = new byte[BufferSize];
            while (true)
            {
                int read = await ReadWithTimeoutAsync(
                    ownedDataStream, buffer, transferContext.RequestTimeout, cancellationToken)
                    .ConfigureAwait(false);
                if (read == 0)
                {
                    break;
                }

                await destination.WriteAsync(buffer.AsMemory(0, read), cancellationToken).ConfigureAwait(false);
                downloaded = checked(downloaded + read);
                DateTimeOffset now = DateTimeOffset.UtcNow;
                if (now - lastProgress >= ProgressInterval || downloaded == totalBytes)
                {
                    lastProgress = now;
                    await progress(downloaded, totalBytes).ConfigureAwait(false);
                }
            }

            await destination.FlushAsync(cancellationToken).ConfigureAwait(false);
            destination.Flush(flushToDisk: true);
        }

        await control.ExpectAsync(226, 250, cancellationToken).ConfigureAwait(false);
        if (totalBytes is long advertisedLength && downloaded != advertisedLength)
        {
            throw new EndOfStreamException(
                $"The FTP data stream ended at {downloaded} bytes; SIZE advertised {advertisedLength} bytes.");
        }
        if (transferContext.ExpectedLength is long expectedFinalLength && downloaded != expectedFinalLength)
        {
            throw new EndOfStreamException(
                $"The FTP data stream ended at {downloaded} bytes; {expectedFinalLength} bytes were expected.");
        }
        await progress(downloaded, totalBytes ?? downloaded).ConfigureAwait(false);
        try
        {
            await control.CommandAsync("QUIT", 221, cancellationToken).ConfigureAwait(false);
        }
        catch (Exception exception) when (exception is IOException or TimeoutException)
        {
            // The transfer already completed; some servers close or stop responding on QUIT.
        }

        return new FtpDownloadResult(downloaded, totalBytes ?? downloaded, lastModified, effectiveOffset > 0);
    }

    private static async Task<TcpClient> ConnectTcpClientAsync(
        string host,
        int port,
        AddressFamily? preferredAddressFamily,
        TimeSpan timeout,
        CancellationToken cancellationToken)
    {
        IPAddress[] addresses = IPAddress.TryParse(host, out IPAddress? literalAddress)
            ? [literalAddress]
            : await WaitWithTimeoutAsync(
                Dns.GetHostAddressesAsync(host, cancellationToken),
                timeout,
                cancellationToken,
                "FTP DNS lookup").ConfigureAwait(false);

        IEnumerable<IPAddress> candidates = preferredAddressFamily is AddressFamily preferred
            ? addresses.OrderByDescending(address => address.AddressFamily == preferred)
            : addresses;
        Exception? lastError = null;
        foreach (IPAddress address in candidates.Distinct())
        {
            TcpClient client = new(address.AddressFamily);
            try
            {
                await WaitWithTimeoutAsync(
                    client.ConnectAsync(address, port, cancellationToken).AsTask(),
                    timeout,
                    cancellationToken,
                    "FTP TCP connect").ConfigureAwait(false);
                return client;
            }
            catch (OperationCanceledException)
            {
                client.Dispose();
                throw;
            }
            catch (Exception exception) when (exception is SocketException or NotSupportedException)
            {
                client.Dispose();
                lastError = exception;
            }
            catch
            {
                client.Dispose();
                throw;
            }
        }

        throw new IOException($"Could not connect to FTP endpoint '{host}:{port}'.", lastError);
    }

    private static async Task<Stream> AuthenticateTlsAsync(
        Stream stream,
        string host,
        TimeSpan timeout,
        CancellationToken cancellationToken)
    {
        SslStream ssl = new(stream, leaveInnerStreamOpen: false);
        try
        {
            await WaitWithTimeoutAsync(
                ssl.AuthenticateAsClientAsync(
                    new SslClientAuthenticationOptions
                    {
                        TargetHost = host,
                        EnabledSslProtocols = SslProtocols.Tls12 | SslProtocols.Tls13,
                        CertificateRevocationCheckMode = System.Security.Cryptography.X509Certificates.X509RevocationMode.Online
                    },
                    cancellationToken),
                timeout,
                cancellationToken,
                "FTP TLS handshake").ConfigureAwait(false);
            return ssl;
        }
        catch
        {
            await ssl.DisposeAsync().ConfigureAwait(false);
            throw;
        }
    }

    private static async Task<long?> TryGetSizeAsync(
        FtpControlConnection control,
        string remotePath,
        CancellationToken cancellationToken)
    {
        FtpResponse response = await control.CommandAsync(
            $"SIZE {remotePath}",
            213,
            500,
            501,
            502,
            550,
            cancellationToken).ConfigureAwait(false);
        return response.Code == 213
            && long.TryParse(response.Message, NumberStyles.None, CultureInfo.InvariantCulture, out long size)
                ? size
                : null;
    }

    private static async Task<DateTimeOffset?> TryGetLastModifiedAsync(
        FtpControlConnection control,
        string remotePath,
        CancellationToken cancellationToken)
    {
        FtpResponse response = await control.CommandAsync(
            $"MDTM {remotePath}",
            213,
            500,
            501,
            502,
            550,
            cancellationToken).ConfigureAwait(false);
        if (response.Code != 213)
        {
            return null;
        }

        string value = response.Message.Trim();
        return DateTimeOffset.TryParseExact(
            value,
            MdtmFormats,
            CultureInfo.InvariantCulture,
            DateTimeStyles.AssumeUniversal | DateTimeStyles.AdjustToUniversal,
            out DateTimeOffset timestamp)
                ? timestamp
                : null;
    }

    private static async Task<(string Host, int Port)> OpenPassiveEndpointAsync(
        FtpControlConnection control,
        string controlHost,
        AddressFamily addressFamily,
        CancellationToken cancellationToken)
    {
        FtpResponse epsv = await control.CommandAsync(
            "EPSV",
            229,
            500,
            501,
            502,
            522,
            cancellationToken).ConfigureAwait(false);
        if (epsv.Code == 229 && TryParseEpsvPort(epsv.RawMessage, out int epsvPort))
        {
            return (controlHost, epsvPort);
        }

        if (addressFamily != AddressFamily.InterNetwork)
        {
            throw new IOException("The FTP server does not support EPSV for this connection.");
        }

        FtpResponse pasv = await control.CommandAsync("PASV", 227, cancellationToken)
            .ConfigureAwait(false);
        if (!TryParsePasv(pasv.RawMessage, out _, out int port))
        {
            throw new IOException("The FTP server returned an invalid PASV endpoint.");
        }

        // Ignore the server-advertised PASV address to prevent FTP bounce attacks.
        return (controlHost, port);
    }

    internal static bool TryParseEpsvPort(string response, out int port)
    {
        port = 0;
        int open = response.IndexOf('(', StringComparison.Ordinal);
        int close = response.IndexOf(')', open + 1);
        if (open < 0 || close <= open)
        {
            return false;
        }

        string payload = response[(open + 1)..close];
        char delimiter = payload.Length > 0 ? payload[0] : '\0';
        string[] fields = payload.Split(delimiter, StringSplitOptions.RemoveEmptyEntries);
        return fields.Length == 1
            && int.TryParse(fields[0], NumberStyles.None, CultureInfo.InvariantCulture, out port)
            && port is > 0 and <= 65535;
    }

    internal static bool TryParsePasv(string response, out string host, out int port)
    {
        host = string.Empty;
        port = 0;
        int open = response.IndexOf('(', StringComparison.Ordinal);
        int close = response.IndexOf(')', open + 1);
        if (open < 0 || close <= open)
        {
            return false;
        }

        string[] values = response[(open + 1)..close].Split(',', StringSplitOptions.TrimEntries);
        if (values.Length != 6 || values.Any(static value =>
            !byte.TryParse(value, NumberStyles.None, CultureInfo.InvariantCulture, out _)))
        {
            return false;
        }

        byte[] octets = values.Select(static value => byte.Parse(value, CultureInfo.InvariantCulture)).ToArray();
        host = string.Join(".", octets.Take(4));
        port = (octets[4] << 8) | octets[5];
        return port > 0;
    }

    private static async Task<T> WaitWithTimeoutAsync<T>(
        Task<T> task,
        TimeSpan? timeout,
        CancellationToken cancellationToken,
        string operation)
    {
        try
        {
            return timeout is null
                ? await task.ConfigureAwait(false)
                : await task.WaitAsync(timeout.Value, cancellationToken).ConfigureAwait(false);
        }
        catch (TimeoutException exception)
        {
            throw new TimeoutException($"{operation} exceeded the configured timeout of {timeout}.", exception);
        }
    }

    private static async Task WaitWithTimeoutAsync(
        Task task,
        TimeSpan? timeout,
        CancellationToken cancellationToken,
        string operation)
    {
        try
        {
            if (timeout is null)
            {
                await task.ConfigureAwait(false);
            }
            else
            {
                await task.WaitAsync(timeout.Value, cancellationToken).ConfigureAwait(false);
            }
        }
        catch (TimeoutException exception)
        {
            throw new TimeoutException($"{operation} exceeded the configured timeout of {timeout}.", exception);
        }
    }

    private static async ValueTask<int> ReadWithTimeoutAsync(
        Stream stream,
        Memory<byte> buffer,
        TimeSpan? timeout,
        CancellationToken cancellationToken)
    {
        try
        {
            ValueTask<int> read = stream.ReadAsync(buffer, cancellationToken);
            return timeout is null
                ? await read.ConfigureAwait(false)
                : await read.AsTask().WaitAsync(timeout.Value, cancellationToken).ConfigureAwait(false);
        }
        catch (TimeoutException exception)
        {
            throw new TimeoutException($"FTP data read exceeded the configured timeout of {timeout}.", exception);
        }
    }

    private static (string Username, string Password) ResolveCredentials(
        Uri source,
        string? username,
        string? password)
    {
        if (!string.IsNullOrWhiteSpace(username))
        {
            return (username, password ?? string.Empty);
        }

        if (!string.IsNullOrWhiteSpace(source.UserInfo))
        {
            string[] parts = source.UserInfo.Split(':', 2);
            return (
                Uri.UnescapeDataString(parts[0]),
                parts.Length == 2 ? Uri.UnescapeDataString(parts[1]) : string.Empty);
        }

        return ("anonymous", "xdm@localhost");
    }

    private static void ValidateCommandValue(string value, string parameterName)
    {
        if (value.Contains('\r') || value.Contains('\n'))
        {
            throw new ArgumentException("FTP command values cannot contain line breaks.", parameterName);
        }
    }

    private sealed record FtpResponse(int Code, string Message, string RawMessage);

    private sealed class FtpControlConnection : IAsyncDisposable
    {
        private Stream _stream;
        private StreamReader _reader;
        private StreamWriter _writer;
        private readonly TimeSpan? _operationTimeout;

        public FtpControlConnection(Stream stream, TimeSpan? operationTimeout)
        {
            _stream = stream;
            _operationTimeout = operationTimeout;
            (_reader, _writer) = CreateTextStreams(stream);
        }

        public Stream DetachStream()
        {
            _reader.Dispose();
            _writer.Dispose();
            return _stream;
        }

        public void AttachStream(Stream stream)
        {
            _stream = stream;
            (_reader, _writer) = CreateTextStreams(stream);
        }

        public Task<FtpResponse> CommandAsync(
            string command,
            int expectedCode,
            CancellationToken cancellationToken)
            => CommandCoreAsync(command, new FtpExpectedCodes(expectedCode), cancellationToken);

        public Task<FtpResponse> CommandAsync(
            string command,
            int expectedCode,
            int alternateCode,
            CancellationToken cancellationToken)
            => CommandCoreAsync(
                command,
                new FtpExpectedCodes(expectedCode, alternateCode),
                cancellationToken);

        public Task<FtpResponse> CommandAsync(
            string command,
            int expectedCode,
            int alternateCode1,
            int alternateCode2,
            int alternateCode3,
            int alternateCode4,
            CancellationToken cancellationToken)
            => CommandCoreAsync(
                command,
                new FtpExpectedCodes(
                    expectedCode,
                    alternateCode1,
                    alternateCode2,
                    alternateCode3,
                    alternateCode4),
                cancellationToken);

        public Task<FtpResponse> ExpectAsync(
            int expectedCode,
            CancellationToken cancellationToken)
            => ExpectCoreAsync(new FtpExpectedCodes(expectedCode), cancellationToken);

        public Task<FtpResponse> ExpectAsync(
            int expectedCode,
            int alternateCode,
            CancellationToken cancellationToken)
            => ExpectCoreAsync(
                new FtpExpectedCodes(expectedCode, alternateCode),
                cancellationToken);

        private async Task<FtpResponse> CommandCoreAsync(
            string command,
            FtpExpectedCodes expectedCodes,
            CancellationToken cancellationToken)
        {
            await WaitWithTimeoutAsync(
                _writer.WriteLineAsync(command.AsMemory(), cancellationToken).AsTask(),
                _operationTimeout,
                cancellationToken,
                "FTP control write").ConfigureAwait(false);
            await WaitWithTimeoutAsync(
                _writer.FlushAsync(cancellationToken),
                _operationTimeout,
                cancellationToken,
                "FTP control flush").ConfigureAwait(false);
            return await ExpectCoreAsync(expectedCodes, cancellationToken).ConfigureAwait(false);
        }

        private async Task<FtpResponse> ExpectCoreAsync(
            FtpExpectedCodes expectedCodes,
            CancellationToken cancellationToken)
        {
            string? firstLine = await WaitWithTimeoutAsync(
                _reader.ReadLineAsync(cancellationToken).AsTask(),
                _operationTimeout,
                cancellationToken,
                "FTP control response").ConfigureAwait(false);
            if (firstLine is null)
            {
                throw new IOException("The FTP server closed the control connection.");
            }

            if (firstLine.Length < 3
                || !int.TryParse(firstLine.AsSpan(0, 3), NumberStyles.None, CultureInfo.InvariantCulture, out int code))
            {
                throw new IOException($"Invalid FTP response: {firstLine}");
            }

            string raw = firstLine;
            if (firstLine.Length > 3 && firstLine[3] == '-')
            {
                string terminator = $"{code.ToString(CultureInfo.InvariantCulture)} ";
                while (true)
                {
                    string? line = await WaitWithTimeoutAsync(
                        _reader.ReadLineAsync(cancellationToken).AsTask(),
                        _operationTimeout,
                        cancellationToken,
                        "FTP multiline response").ConfigureAwait(false);
                    if (line is null)
                    {
                        throw new IOException("The FTP server closed a multiline response.");
                    }

                    raw += $"\n{line}";
                    if (line.StartsWith(terminator, StringComparison.Ordinal))
                    {
                        firstLine = line;
                        break;
                    }
                }
            }

            if (!expectedCodes.Contains(code))
            {
                throw new IOException($"FTP command failed with {code}: {raw}");
            }

            string message = firstLine.Length > 4 ? firstLine[4..] : string.Empty;
            return new FtpResponse(code, message, raw);
        }

        public async ValueTask DisposeAsync()
        {
            await _writer.DisposeAsync().ConfigureAwait(false);
            _reader.Dispose();
            await _stream.DisposeAsync().ConfigureAwait(false);
        }

        private readonly record struct FtpExpectedCodes(
            int First,
            int Second = 0,
            int Third = 0,
            int Fourth = 0,
            int Fifth = 0)
        {
            public bool Contains(int code)
                => code == First
                    || (Second != 0 && code == Second)
                    || (Third != 0 && code == Third)
                    || (Fourth != 0 && code == Fourth)
                    || (Fifth != 0 && code == Fifth);
        }

        private static (StreamReader Reader, StreamWriter Writer) CreateTextStreams(Stream stream)
        {
            StreamReader reader = new(stream, System.Text.Encoding.ASCII, false, 1024, leaveOpen: true);
            StreamWriter writer = new(stream, System.Text.Encoding.ASCII, 1024, leaveOpen: true)
            {
                NewLine = "\r\n",
                AutoFlush = true
            };
            return (reader, writer);
        }
    }
}
