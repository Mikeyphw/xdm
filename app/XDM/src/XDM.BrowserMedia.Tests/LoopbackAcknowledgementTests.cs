using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Net.Sockets;
using XDM.BrowserIntegration;

namespace XDM.BrowserMedia.Tests;

public sealed class LoopbackAcknowledgementTests
{
    [Fact]
    public async Task DoesNotAcknowledgeUntilCaptureHandlerAccepts()
    {
        int port = GetFreeTcpPort();
        const string token = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        using LoopbackBrowserIntegrationService service = new(port, token);
        TaskCompletionSource entered = new(TaskCreationOptions.RunContinuationsAsynchronously);
        TaskCompletionSource release = new(TaskCreationOptions.RunContinuationsAsynchronously);
        service.CaptureReceived += (_, eventArgs) => _ = Task.Run(async () =>
        {
            entered.TrySetResult();
            await release.Task;
            eventArgs.Accept("queued-download");
        });
        await service.InitializeAsync();
        Assert.True(service.Current.IsListening, service.Current.LastError);

        using HttpClient client = new();
        byte[] payload = BrowserCaptureProtocol.Serialize(new BrowserCaptureRequest(
            new Uri("https://example.test/archive.zip?token=private"),
            RequestId: "ack-fixture"));
        using ByteArrayContent content = new(payload);
        content.Headers.ContentType = new MediaTypeHeaderValue("application/json");
        using HttpRequestMessage request = new(HttpMethod.Post, $"http://127.0.0.1:{port}/capture")
        {
            Content = content
        };
        request.Headers.Add("X-XDM-Token", token);

        Task<HttpResponseMessage> responseTask = client.SendAsync(request);
        await entered.Task.WaitAsync(TimeSpan.FromSeconds(5));
        Assert.False(responseTask.IsCompleted);
        release.TrySetResult();

        using HttpResponseMessage response = await responseTask.WaitAsync(TimeSpan.FromSeconds(5));
        BrowserCaptureAcknowledgement? acknowledgement = await response.Content
            .ReadFromJsonAsync<BrowserCaptureAcknowledgement>();

        Assert.Equal(HttpStatusCode.Accepted, response.StatusCode);
        Assert.True(acknowledgement!.Accepted);
        Assert.Equal("queued-download", acknowledgement.DownloadId);
        Assert.Equal("https://example.test/archive.zip", service.Current.LastCapturedUrl);
    }

    [Fact]
    public async Task RejectsUnauthenticatedCapture()
    {
        int port = GetFreeTcpPort();
        using LoopbackBrowserIntegrationService service = new(
            port,
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        await service.InitializeAsync();
        Assert.True(service.Current.IsListening, service.Current.LastError);

        using HttpClient client = new();
        using HttpResponseMessage response = await client.PostAsJsonAsync(
            $"http://127.0.0.1:{port}/capture",
            new BrowserCaptureRequest(new Uri("https://example.test/file.zip")));

        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
    }

    private static int GetFreeTcpPort()
    {
        TcpListener listener = new(IPAddress.Loopback, 0);
        listener.Start();
        int port = ((IPEndPoint)listener.LocalEndpoint).Port;
        listener.Stop();
        return port;
    }
}
