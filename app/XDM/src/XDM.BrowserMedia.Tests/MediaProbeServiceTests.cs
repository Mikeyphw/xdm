using System.Net;
using System.Net.Http.Headers;
using XDM.Media;

namespace XDM.BrowserMedia.Tests;

public sealed class MediaProbeServiceTests
{
    [Fact]
    public async Task DetectsHlsVariants()
    {
        const string manifest = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000
            low.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=1600000
            high.m3u8
            """;
        using HttpClient client = new(new StaticResponseHandler(
            manifest,
            "application/vnd.apple.mpegurl"));
        MediaProbeService service = new(client);

        MediaProbeResult result = await service.ProbeAsync(new Uri("https://example.test/master.m3u8"));

        Assert.Equal(MediaKind.Hls, result.Kind);
        Assert.Equal(2, result.VariantCount);
    }

    [Fact]
    public async Task DetectsDashRepresentations()
    {
        const string manifest = """
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011">
              <Period><AdaptationSet>
                <Representation id="video-1" />
                <Representation id="video-2" />
              </AdaptationSet></Period>
            </MPD>
            """;
        using HttpClient client = new(new StaticResponseHandler(manifest, "application/dash+xml"));
        MediaProbeService service = new(client);

        MediaProbeResult result = await service.ProbeAsync(new Uri("https://example.test/manifest.mpd"));

        Assert.Equal(MediaKind.Dash, result.Kind);
        Assert.Equal(2, result.VariantCount);
    }

    private sealed class StaticResponseHandler(string content, string contentType) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            StringContent body = new(content);
            body.Headers.ContentType = MediaTypeHeaderValue.Parse(contentType);
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK) { Content = body });
        }
    }
}
