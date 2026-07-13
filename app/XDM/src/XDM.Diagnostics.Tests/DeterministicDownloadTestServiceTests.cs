using System.Net;
using System.Net.Http.Headers;

namespace XDM.Diagnostics.Tests;

public sealed class DeterministicDownloadTestServiceTests
{
    [Fact]
    public async Task RunAsyncAcceptsExactlyOneMebibyteAndDoesNotPersistPayload()
    {
        byte[] payload = new byte[DeterministicDownloadTestService.ExpectedBytes];
        using HttpClient client = new(new PayloadHandler(payload));
        DiagnosticEventStore events = new();
        using DeterministicDownloadTestService service = new(client, events);
        DeterministicDownloadTestResult result = await service.RunAsync();

        Assert.True(result.Succeeded);
        Assert.Equal(payload.Length, result.ReceivedBytes);
        Assert.Equal(64, result.Sha256.Length);
        Assert.Same(result, service.LastResult);
        Assert.Contains(events.Snapshot(), static item => item.Code == "XDM-DIAGNOSTICS-TEST-DOWNLOAD");
    }

    [Fact]
    public async Task RunAsyncRejectsOversizedResponse()
    {
        byte[] payload = new byte[DeterministicDownloadTestService.ExpectedBytes + 1];
        using HttpClient client = new(new PayloadHandler(payload, includeLength: false));
        using DeterministicDownloadTestService service = new(client, new DiagnosticEventStore());
        DeterministicDownloadTestResult result = await service.RunAsync();

        Assert.False(result.Succeeded);
        Assert.Contains("exceeded", result.Summary, StringComparison.OrdinalIgnoreCase);
    }

    private sealed class PayloadHandler(byte[] payload, bool includeLength = true) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken)
        {
            ByteArrayContent content = new(payload);
            if (!includeLength)
            {
                content.Headers.ContentLength = null;
            }
            else
            {
                content.Headers.ContentLength = payload.Length;
            }

            content.Headers.ContentType = new MediaTypeHeaderValue("application/octet-stream");
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK)
            {
                RequestMessage = request,
                Content = content
            });
        }
    }
}
