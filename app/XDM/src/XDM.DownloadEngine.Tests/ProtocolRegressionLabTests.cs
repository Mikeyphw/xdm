using System.Net;
using System.Net.Http.Headers;
using System.Text;
using XDM.DownloadEngine.Tests.FaultLab;

namespace XDM.DownloadEngine.Tests;

public sealed class ProtocolRegressionLabTests
{
    [Fact]
    public async Task ValidRangeReturnsRequestedSliceAndIdentity()
    {
        CancellationToken cancellationToken = CancellationToken.None;
        await using ProtocolLabScenarioServer server = new();
        using HttpClient client = CreateClient();
        const int offset = 4096;
        using HttpRequestMessage request = new(HttpMethod.Get, server.GetUri("range/valid"));
        request.Headers.Range = new RangeHeaderValue(offset, null);
        using HttpResponseMessage response = await client.SendAsync(request, cancellationToken);
        byte[] body = await response.Content.ReadAsByteArrayAsync(cancellationToken);

        Assert.Equal(HttpStatusCode.PartialContent, response.StatusCode);
        Assert.Equal((long)offset, response.Content.Headers.ContentRange?.From);
        Assert.Equal((long)server.Payload.Length, response.Content.Headers.ContentRange?.Length);
        Assert.Equal("\"protocol-lab-v1\"", response.Headers.ETag?.Tag);
        Assert.Equal(server.Payload[offset..], body);
    }

    [Fact]
    public async Task InvalidRangeCanContradictTheRequestedOffset()
    {
        CancellationToken cancellationToken = CancellationToken.None;
        await using ProtocolLabScenarioServer server = new();
        using HttpClient client = CreateClient();
        const int offset = 2048;
        using HttpRequestMessage request = new(HttpMethod.Get, server.GetUri("range/invalid"));
        request.Headers.Range = new RangeHeaderValue(offset, null);
        using HttpResponseMessage response = await client.SendAsync(request, cancellationToken);

        Assert.Equal(HttpStatusCode.PartialContent, response.StatusCode);
        Assert.Equal((long)offset + 1, response.Content.Headers.ContentRange?.From);
        Assert.NotEqual((long)server.Payload.Length, response.Content.Headers.ContentRange?.Length);
    }

    [Fact]
    public async Task IgnoredRangeReturnsTheCompleteRepresentation()
    {
        CancellationToken cancellationToken = CancellationToken.None;
        await using ProtocolLabScenarioServer server = new();
        using HttpClient client = CreateClient();
        using HttpRequestMessage request = new(HttpMethod.Get, server.GetUri("range/ignored"));
        request.Headers.Range = new RangeHeaderValue(1024, null);
        using HttpResponseMessage response = await client.SendAsync(request, cancellationToken);
        byte[] body = await response.Content.ReadAsByteArrayAsync(cancellationToken);

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Null(response.Content.Headers.ContentRange);
        Assert.Equal(server.Payload, body);
    }

    [Fact]
    public async Task RedirectChainTerminatesAtTheDeterministicPayload()
    {
        CancellationToken cancellationToken = CancellationToken.None;
        await using ProtocolLabScenarioServer server = new();
        using HttpClient client = CreateClient();
        byte[] body = await client.GetByteArrayAsync(server.GetUri("redirect/3"), cancellationToken);

        Assert.Equal(server.Payload, body);
        Assert.Equal(5, server.RequestCount);
    }

    [Fact]
    public async Task ExpiringUrlRejectsReuseAfterTheFirstRequest()
    {
        CancellationToken cancellationToken = CancellationToken.None;
        await using ProtocolLabScenarioServer server = new();
        using HttpClient client = CreateClient();
        Uri source = server.GetUri("expiring/valid");
        using HttpResponseMessage first = await client.GetAsync(source, cancellationToken);
        using HttpResponseMessage second = await client.GetAsync(source, cancellationToken);

        Assert.Equal(HttpStatusCode.OK, first.StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden, second.StatusCode);
    }

    [Fact]
    public async Task ChangedEntityTagIsObservableAcrossRequests()
    {
        CancellationToken cancellationToken = CancellationToken.None;
        await using ProtocolLabScenarioServer server = new();
        using HttpClient client = CreateClient();
        Uri source = server.GetUri("etag/changing");
        using HttpResponseMessage first = await client.GetAsync(source, cancellationToken);
        using HttpResponseMessage second = await client.GetAsync(source, cancellationToken);

        Assert.Equal("\"protocol-lab-v1\"", first.Headers.ETag?.Tag);
        Assert.Equal("\"protocol-lab-v2\"", second.Headers.ETag?.Tag);
    }

    [Fact]
    public async Task InterruptedResponseSupportsAValidatedFollowUpRange()
    {
        CancellationToken cancellationToken = CancellationToken.None;
        await using ProtocolLabScenarioServer server = new();
        using HttpClient client = CreateClient();
        Uri source = server.GetUri("interrupt");
        using HttpResponseMessage interrupted = await client.GetAsync(
            source,
            HttpCompletionOption.ResponseHeadersRead,
            cancellationToken);
        await AssertPrematureEndAsync(
            () => interrupted.Content.ReadAsByteArrayAsync(cancellationToken));

        int offset = server.Payload.Length / 2;
        using HttpRequestMessage resume = new(HttpMethod.Get, source);
        resume.Headers.Range = new RangeHeaderValue(offset, null);
        resume.Headers.IfRange = new RangeConditionHeaderValue("\"protocol-lab-v1\"");
        using HttpResponseMessage resumed = await client.SendAsync(resume, cancellationToken);
        byte[] remainder = await resumed.Content.ReadAsByteArrayAsync(cancellationToken);

        Assert.Equal(HttpStatusCode.PartialContent, resumed.StatusCode);
        Assert.Equal(server.Payload[offset..], remainder);
    }

    [Fact]
    public async Task ChunkedResponseIsDecodedWithoutAContentLength()
    {
        CancellationToken cancellationToken = CancellationToken.None;
        await using ProtocolLabScenarioServer server = new();
        using HttpClient client = CreateClient();
        using HttpResponseMessage response = await client.GetAsync(
            server.GetUri("chunked"),
            HttpCompletionOption.ResponseHeadersRead,
            cancellationToken);

        Assert.True(response.Headers.TransferEncodingChunked);
        Assert.Null(response.Content.Headers.ContentLength);

        byte[] body = await response.Content.ReadAsByteArrayAsync(cancellationToken);
        Assert.Equal(server.Payload, body);
    }

    [Fact]
    public async Task IncorrectContentLengthProducesAPrematureEndFailure()
    {
        CancellationToken cancellationToken = CancellationToken.None;
        await using ProtocolLabScenarioServer server = new();
        using HttpClient client = CreateClient();
        using HttpResponseMessage response = await client.GetAsync(
            server.GetUri("length/short"),
            HttpCompletionOption.ResponseHeadersRead,
            cancellationToken);

        await AssertPrematureEndAsync(
            () => response.Content.ReadAsByteArrayAsync(cancellationToken));
    }

    [Fact]
    public async Task BasicAuthenticationChallengeAcceptsTheExpectedCredentials()
    {
        CancellationToken cancellationToken = CancellationToken.None;
        await using ProtocolLabScenarioServer server = new();
        using HttpClient client = CreateClient();
        Uri source = server.GetUri("auth/basic");
        using HttpResponseMessage challenge = await client.GetAsync(source, cancellationToken);
        using HttpRequestMessage authenticatedRequest = new(HttpMethod.Get, source);
        authenticatedRequest.Headers.Authorization = new AuthenticationHeaderValue(
            "Basic",
            Convert.ToBase64String(Encoding.UTF8.GetBytes("user:pass")));
        using HttpResponseMessage authenticated = await client.SendAsync(authenticatedRequest, cancellationToken);

        Assert.Equal(HttpStatusCode.Unauthorized, challenge.StatusCode);
        Assert.Equal("Basic", challenge.Headers.WwwAuthenticate.Single().Scheme);
        Assert.Equal(HttpStatusCode.OK, authenticated.StatusCode);
    }

    [Fact]
    public async Task ProxyAuthenticationFailureIsObservableWithoutContactingTheOrigin()
    {
        CancellationToken cancellationToken = CancellationToken.None;
        await using DeterministicHttpFaultServer proxy = new(
            static (_, _) => new FaultResponse(
                407,
                [],
                new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase)
                {
                    ["Proxy-Authenticate"] = "Basic realm=\"XDM Protocol Lab Proxy\""
                },
                0,
                0));
        using HttpClient client = new(new SocketsHttpHandler
        {
            Proxy = new WebProxy(proxy.BaseUri),
            UseProxy = true
        })
        {
            Timeout = TimeSpan.FromSeconds(10)
        };
        using HttpResponseMessage response = await client.GetAsync(
            new Uri("http://origin.invalid/file.bin"),
            cancellationToken);

        Assert.Equal(HttpStatusCode.ProxyAuthenticationRequired, response.StatusCode);
        Assert.Contains("origin.invalid", Assert.Single(proxy.Requests).Target, StringComparison.Ordinal);
    }

    [Fact]
    public async Task RateLimitEndpointRecoversOnTheNextAttempt()
    {
        CancellationToken cancellationToken = CancellationToken.None;
        await using ProtocolLabScenarioServer server = new();
        using HttpClient client = CreateClient();
        Uri source = server.GetUri("rate-limit");
        using HttpResponseMessage limited = await client.GetAsync(source, cancellationToken);
        using HttpResponseMessage recovered = await client.GetAsync(source, cancellationToken);

        Assert.Equal(HttpStatusCode.TooManyRequests, limited.StatusCode);
        Assert.NotNull(limited.Headers.RetryAfter?.Delta);
        Assert.Equal(TimeSpan.Zero, limited.Headers.RetryAfter!.Delta!.Value);
        Assert.Equal(HttpStatusCode.OK, recovered.StatusCode);
    }

    [Fact]
    public async Task SelfSignedTlsEndpointIsRejectedByTheDefaultClient()
    {
        CancellationToken cancellationToken = CancellationToken.None;
        await using ProtocolLabScenarioServer server = new(useTls: true);
        using HttpClient client = CreateClient();

        await Assert.ThrowsAsync<HttpRequestException>(
            () => client.GetAsync(server.GetUri("payload"), cancellationToken));
    }

    [Fact]
    public async Task VeryLargeLogicalFileUsesSixtyFourBitContentRangeMetadata()
    {
        CancellationToken cancellationToken = CancellationToken.None;
        await using ProtocolLabScenarioServer server = new();
        using HttpClient client = CreateClient();
        const long expectedLength = 5L * 1024 * 1024 * 1024 * 1024;
        using HttpRequestMessage request = new(HttpMethod.Get, server.GetUri("large"));
        request.Headers.Range = new RangeHeaderValue(expectedLength - 1, null);
        using HttpResponseMessage response = await client.SendAsync(request, cancellationToken);
        byte[] body = await response.Content.ReadAsByteArrayAsync(cancellationToken);

        Assert.Equal(expectedLength, response.Content.Headers.ContentRange?.Length);
        Assert.Equal(expectedLength - 1, response.Content.Headers.ContentRange?.From);
        Assert.Single(body);
    }

    [Fact]
    public async Task MalformedFilenameRemainsAvailableForSanitizationTests()
    {
        CancellationToken cancellationToken = CancellationToken.None;
        await using ProtocolLabScenarioServer server = new();
        using HttpClient client = CreateClient();
        using HttpResponseMessage response = await client.GetAsync(
            server.GetUri("filename/malformed"),
            cancellationToken);
        string disposition = Assert.Single(response.Content.Headers.GetValues("Content-Disposition"));

        Assert.Contains("CON?", disposition, StringComparison.Ordinal);
        Assert.Contains("%ZZ", disposition, StringComparison.Ordinal);
    }

    [Fact]
    public async Task StructurallyMalformedHeaderIsRejectedByTheHttpStack()
    {
        CancellationToken cancellationToken = CancellationToken.None;
        await using ProtocolLabScenarioServer server = new();
        using HttpClient client = CreateClient();

        await Assert.ThrowsAnyAsync<HttpRequestException>(
            () => client.GetAsync(server.GetUri("header/malformed"), cancellationToken));
    }

    [Fact]
    public async Task HlsAndDashFixturesReferenceDeterministicSegments()
    {
        CancellationToken cancellationToken = CancellationToken.None;
        await using ProtocolLabScenarioServer server = new();
        using HttpClient client = CreateClient();
        string hls = await client.GetStringAsync(server.GetUri("media/hls/master.m3u8"), cancellationToken);
        string dash = await client.GetStringAsync(server.GetUri("media/dash/manifest.mpd"), cancellationToken);

        Assert.Contains("#EXTM3U", hls, StringComparison.Ordinal);
        Assert.Contains(server.GetUri("media/hls/segment.ts").AbsoluteUri, hls, StringComparison.Ordinal);
        Assert.Contains("<MPD", dash, StringComparison.Ordinal);
        Assert.Contains(server.GetUri("media/dash/segment.m4s").AbsoluteUri, dash, StringComparison.Ordinal);
    }

    private static async Task AssertPrematureEndAsync(Func<Task> action)
    {
        Exception? exception = await Record.ExceptionAsync(action);

        Assert.NotNull(exception);
        if (exception is HttpRequestException requestException)
        {
            Assert.IsAssignableFrom<IOException>(requestException.InnerException);
            return;
        }

        Assert.IsAssignableFrom<IOException>(exception);
    }

    private static HttpClient CreateClient()
        => new() { Timeout = TimeSpan.FromSeconds(10) };
}
