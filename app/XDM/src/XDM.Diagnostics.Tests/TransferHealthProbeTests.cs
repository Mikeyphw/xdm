using System.Net;
using System.Net.Sockets;
using System.Text;

namespace XDM.Diagnostics.Tests;

public sealed class TransferHealthProbeTests
{
    [Fact]
    public async Task ProbeMeasuresRangeBehaviorAndBoundedDiskWrite()
    {
        await using RangeProbeServer server = new();
        string destination = Path.Combine(Path.GetTempPath(), $"xdm-health-{Guid.NewGuid():N}");
        Directory.CreateDirectory(destination);
        try
        {
            using HttpClient client = new(new SocketsHttpHandler { UseProxy = false });
            TransferHealthProbe probe = new(
                client,
                new TransferHealthProbeOptions(
                    TimeSpan.FromSeconds(8),
                    TimeSpan.FromSeconds(3),
                    4096));

            TransferHealthProbeResult result = await probe.ProbeAsync(server.Uri, destination);

            Assert.True(result.Succeeded);
            Assert.True(result.RangeSupported);
            Assert.NotNull(result.DiskWriteBytesPerSecond);
            Assert.Contains(result.Stages, static stage => stage.Name == "DNS" && stage.Status == TransferHealthProbeStatus.Passed);
            Assert.Contains(result.Stages, static stage => stage.Name == "TCP" && stage.Status == TransferHealthProbeStatus.Passed);
            Assert.Contains(result.Stages, static stage => stage.Name == "TLS" && stage.Status == TransferHealthProbeStatus.Skipped);
            Assert.Contains(result.Stages, static stage => stage.Name == "HTTP + range" && stage.Status == TransferHealthProbeStatus.Passed);
            Assert.Contains(result.Stages, static stage => stage.Name == "Destination disk" && stage.Status == TransferHealthProbeStatus.Passed);
            Assert.Empty(Directory.EnumerateFiles(destination, ".xdm-health-*.tmp"));
        }
        finally
        {
            Directory.Delete(destination, recursive: true);
        }
    }

    private sealed class RangeProbeServer : IAsyncDisposable
    {
        private readonly TcpListener _listener = new(IPAddress.Loopback, 0);
        private readonly CancellationTokenSource _cancellation = new();
        private readonly Task _acceptLoop;

        public RangeProbeServer()
        {
            _listener.Start();
            int port = ((IPEndPoint)_listener.LocalEndpoint).Port;
            Uri = new Uri($"http://127.0.0.1:{port}/fixture.bin");
            _acceptLoop = AcceptLoopAsync(_cancellation.Token);
        }

        public Uri Uri { get; }

        public async ValueTask DisposeAsync()
        {
            _cancellation.Cancel();
            _listener.Stop();
            try
            {
                await _acceptLoop.ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
            }
            catch (SocketException)
            {
            }
            _cancellation.Dispose();
        }

        private async Task AcceptLoopAsync(CancellationToken cancellationToken)
        {
            while (!cancellationToken.IsCancellationRequested)
            {
                TcpClient client = await _listener.AcceptTcpClientAsync(cancellationToken).ConfigureAwait(false);
                _ = HandleClientAsync(client, cancellationToken);
            }
        }

        private static async Task HandleClientAsync(TcpClient client, CancellationToken cancellationToken)
        {
            using (client)
            {
                NetworkStream stream = client.GetStream();
                byte[] buffer = new byte[4096];
                using MemoryStream request = new();
                while (request.Length < 16 * 1024)
                {
                    int read;
                    try
                    {
                        read = await stream.ReadAsync(buffer, cancellationToken).ConfigureAwait(false);
                    }
                    catch (IOException)
                    {
                        return;
                    }

                    if (read == 0)
                    {
                        return;
                    }

                    request.Write(buffer, 0, read);
                    string text = Encoding.ASCII.GetString(request.GetBuffer(), 0, checked((int)request.Length));
                    if (text.Contains("\r\n\r\n", StringComparison.Ordinal))
                    {
                        break;
                    }
                }

                byte[] response = Encoding.ASCII.GetBytes(
                    "HTTP/1.1 206 Partial Content\r\n"
                    + "Content-Length: 1\r\n"
                    + "Content-Range: bytes 0-0/1024\r\n"
                    + "Accept-Ranges: bytes\r\n"
                    + "ETag: \"probe\"\r\n"
                    + "Connection: close\r\n\r\nX");
                await stream.WriteAsync(response, cancellationToken).ConfigureAwait(false);
                await stream.FlushAsync(cancellationToken).ConfigureAwait(false);
            }
        }
    }
}
