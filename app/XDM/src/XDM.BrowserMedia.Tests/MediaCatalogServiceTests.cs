using XDM.Media;

namespace XDM.BrowserMedia.Tests;

public sealed class MediaCatalogServiceTests
{
    [Fact]
    public async Task DiscoversHlsFormatsAndSubtitles()
    {
        using HttpClient client = new(new RoutingHandler(request =>
        {
            string path = request.RequestUri!.AbsolutePath;
            if (path.EndsWith("master.m3u8", StringComparison.Ordinal))
            {
                return RoutingHandler.Text(MediaFixture.Read("hls-master.m3u8"), "application/vnd.apple.mpegurl");
            }

            return RoutingHandler.Text(
                "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4,\nsegment.ts\n#EXT-X-ENDLIST\n",
                "application/vnd.apple.mpegurl");
        }));
        MediaCatalogService service = new(client, new NullYtDlpProvider());

        MediaCatalog catalog = await service.GetCatalogAsync(new Uri("https://media.example.test/master.m3u8"));

        Assert.Equal(MediaKind.Hls, catalog.Kind);
        Assert.False(catalog.IsLive);
        Assert.Equal(2, catalog.VideoFormats.Count);
        Assert.Single(catalog.AudioFormats);
        Assert.Single(catalog.SubtitleFormats);
    }

    [Fact]
    public async Task DiscoversDashRepresentations()
    {
        using HttpClient client = new(new RoutingHandler(_ =>
            RoutingHandler.Text(MediaFixture.Read("dash-static.mpd"), "application/dash+xml")));
        MediaCatalogService service = new(client, new NullYtDlpProvider());

        MediaCatalog catalog = await service.GetCatalogAsync(new Uri("https://media.example.test/manifest.mpd"));

        Assert.Equal(MediaKind.Dash, catalog.Kind);
        Assert.Single(catalog.VideoFormats);
        Assert.Single(catalog.AudioFormats);
    }

    [Fact]
    public async Task FallsBackToProviderForHtmlPage()
    {
        MediaCatalog expected = new(
            new Uri("https://example.test/watch"),
            MediaKind.ExternalProvider,
            "provider",
            false,
            [new MediaFormat("direct", MediaStreamKind.Muxed, new Uri("https://cdn.example.test/a.mp4"), "mp4", null, null, null, null, null, null, null, true, false)],
            "provider result",
            "test");
        using HttpClient client = new(new RoutingHandler(_ => RoutingHandler.Text("<html></html>", "text/html")));
        MediaCatalogService service = new(client, new FixedYtDlpProvider(expected));

        MediaCatalog actual = await service.GetCatalogAsync(expected.Source);

        Assert.Same(expected, actual);
    }

    private sealed class NullYtDlpProvider : IYtDlpProvider
    {
        public Task<ExternalToolHealth> GetHealthAsync(CancellationToken cancellationToken = default)
            => Task.FromResult(new ExternalToolHealth("yt-dlp", false, null, null, "missing"));

        public Task<MediaCatalog?> TryGetCatalogAsync(
            Uri source,
            MediaRequestMetadata metadata,
            CancellationToken cancellationToken = default)
            => Task.FromResult<MediaCatalog?>(null);
    }

    private sealed class FixedYtDlpProvider(MediaCatalog catalog) : IYtDlpProvider
    {
        public Task<ExternalToolHealth> GetHealthAsync(CancellationToken cancellationToken = default)
            => Task.FromResult(new ExternalToolHealth("yt-dlp", true, "test", "test", "ok"));

        public Task<MediaCatalog?> TryGetCatalogAsync(
            Uri source,
            MediaRequestMetadata metadata,
            CancellationToken cancellationToken = default)
            => Task.FromResult<MediaCatalog?>(catalog);
    }
}
