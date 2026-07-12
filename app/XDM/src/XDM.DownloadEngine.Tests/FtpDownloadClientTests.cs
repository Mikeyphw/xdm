using System.Globalization;
using System.Net;
using System.Net.Sockets;
using System.Text;
using XDM.DownloadEngine;

namespace XDM.DownloadEngine.Tests;

public sealed class FtpDownloadClientTests
{
    [Fact]
    public async Task DownloadsAndResumesFromPassiveFtpServer()
    {
        byte[] payload = Enumerable.Range(0, 4096).Select(static index => (byte)(index % 251)).ToArray();
        await using ScriptedFtpServer server = new(payload);
        string directory = Path.Combine(Path.GetTempPath(), $"xdm-ftp-{Guid.NewGuid():N}");
        Directory.CreateDirectory(directory);
        string destination = Path.Combine(directory, "payload.part");
        await File.WriteAllBytesAsync(destination, payload[..512]);

        try
        {
            FtpDownloadClient client = new();
            List<long> progress = [];
            FtpDownloadResult result = await client.DownloadAsync(
                new Uri($"ftp://user:secret@127.0.0.1:{server.Port}/payload.bin"),
                destination,
                512,
                null,
                null,
                (downloaded, _) =>
                {
                    progress.Add(downloaded);
                    return ValueTask.CompletedTask;
                });

            Assert.True(result.Resumed);
            Assert.Equal(payload.Length, result.TotalBytes);
            Assert.Equal(payload.Length, result.DownloadedBytes);
            Assert.Equal(payload, await File.ReadAllBytesAsync(destination));
            Assert.Contains(payload.Length, progress);
            Assert.Equal("user", server.Username);
            Assert.Equal("secret", server.Password);
        }
        finally
        {
            Directory.Delete(directory, recursive: true);
        }
    }

    [Theory]
    [InlineData("229 Entering Extended Passive Mode (|||49152|)", true, 49152)]
    [InlineData("229 (!!!443!)", true, 443)]
    [InlineData("229 malformed", false, 0)]
    public void ParsesExtendedPassiveResponses(string response, bool expected, int expectedPort)
    {
        bool parsed = FtpDownloadClient.TryParseEpsvPort(response, out int port);
        Assert.Equal(expected, parsed);
        Assert.Equal(expectedPort, port);
    }

    [Fact]
    public async Task RejectsCommandInjectionInRemotePath()
    {
        FtpDownloadClient client = new();
        Uri source = new("ftp://example.test/file%0D%0ADELE%20other.bin");

        await Assert.ThrowsAsync<ArgumentException>(() => client.DownloadAsync(
            source,
            Path.GetTempFileName(),
            0,
            null,
            null,
            static (_, _) => ValueTask.CompletedTask));
    }

    private sealed class ScriptedFtpServer : IAsyncDisposable
    {
        private readonly byte[] _payload;
        private readonly TcpListener _controlListener;
        private readonly CancellationTokenSource _cancellation = new();
        private readonly Task _serverTask;
        private TcpListener? _dataListener;
        private long _restartOffset;

        public ScriptedFtpServer(byte[] payload)
        {
            _payload = payload;
            _controlListener = new TcpListener(IPAddress.Loopback, 0);
            _controlListener.Start();
            Port = ((IPEndPoint)_controlListener.LocalEndpoint).Port;
            _serverTask = RunAsync(_cancellation.Token);
        }

        public int Port { get; }

        public string? Username { get; private set; }

        public string? Password { get; private set; }

        public async ValueTask DisposeAsync()
        {
            _cancellation.Cancel();
            _dataListener?.Stop();
            _controlListener.Stop();
            try
            {
                await _serverTask.ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
            }
            catch (SocketException) when (_cancellation.IsCancellationRequested)
            {
            }
            _cancellation.Dispose();
        }

        private async Task RunAsync(CancellationToken cancellationToken)
        {
            using TcpClient client = await _controlListener.AcceptTcpClientAsync(cancellationToken)
                .ConfigureAwait(false);
            using NetworkStream stream = client.GetStream();
            using StreamReader reader = new(stream, Encoding.ASCII, false, 1024, leaveOpen: true);
            using StreamWriter writer = new(stream, Encoding.ASCII, 1024, leaveOpen: true)
            {
                AutoFlush = true,
                NewLine = "\r\n"
            };
            await writer.WriteLineAsync("220 Test FTP ready").ConfigureAwait(false);

            while (!cancellationToken.IsCancellationRequested)
            {
                string? commandLine = await reader.ReadLineAsync(cancellationToken).ConfigureAwait(false);
                if (commandLine is null)
                {
                    return;
                }

                string command;
                string argument;
                int separator = commandLine.IndexOf(' ');
                if (separator < 0)
                {
                    command = commandLine;
                    argument = string.Empty;
                }
                else
                {
                    command = commandLine[..separator];
                    argument = commandLine[(separator + 1)..];
                }

                switch (command.ToUpperInvariant())
                {
                    case "USER":
                        Username = argument;
                        await writer.WriteLineAsync("331 Password required").ConfigureAwait(false);
                        break;
                    case "PASS":
                        Password = argument;
                        await writer.WriteLineAsync("230 Logged in").ConfigureAwait(false);
                        break;
                    case "TYPE":
                        await writer.WriteLineAsync("200 Type set").ConfigureAwait(false);
                        break;
                    case "SIZE":
                        await writer.WriteLineAsync($"213 {_payload.Length.ToString(CultureInfo.InvariantCulture)}")
                            .ConfigureAwait(false);
                        break;
                    case "MDTM":
                        await writer.WriteLineAsync("213 20260712010102").ConfigureAwait(false);
                        break;
                    case "REST":
                        _restartOffset = long.Parse(argument, CultureInfo.InvariantCulture);
                        await writer.WriteLineAsync("350 Restart accepted").ConfigureAwait(false);
                        break;
                    case "EPSV":
                        _dataListener = new TcpListener(IPAddress.Loopback, 0);
                        _dataListener.Start();
                        int dataPort = ((IPEndPoint)_dataListener.LocalEndpoint).Port;
                        await writer.WriteLineAsync($"229 Entering Extended Passive Mode (|||{dataPort}|)")
                            .ConfigureAwait(false);
                        break;
                    case "RETR":
                        await writer.WriteLineAsync("150 Opening data connection").ConfigureAwait(false);
                        using (TcpClient dataClient = await _dataListener!.AcceptTcpClientAsync(cancellationToken)
                            .ConfigureAwait(false))
                        await using (NetworkStream data = dataClient.GetStream())
                        {
                            await data.WriteAsync(
                                _payload.AsMemory(checked((int)_restartOffset)),
                                cancellationToken).ConfigureAwait(false);
                        }
                        _dataListener.Stop();
                        _dataListener = null;
                        await writer.WriteLineAsync("226 Transfer complete").ConfigureAwait(false);
                        break;
                    case "QUIT":
                        await writer.WriteLineAsync("221 Goodbye").ConfigureAwait(false);
                        return;
                    default:
                        await writer.WriteLineAsync("502 Not implemented").ConfigureAwait(false);
                        break;
                }
            }
        }
    }
}
