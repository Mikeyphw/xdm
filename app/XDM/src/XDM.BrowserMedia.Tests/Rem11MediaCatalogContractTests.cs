using System.Net;
using System.Net.Http.Headers;
using XDM.Media;

namespace XDM.BrowserMedia.Tests;

public sealed class Rem11MediaCatalogContractTests
{
    [Fact]
    public async Task HlsMasterChildrenResolveAgainstFinalRedirectedManifestUri()
    {
        const string master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1200000,RESOLUTION=1280x720,CODECS="avc1.42e01e,mp4a.40.2"
            video/720.m3u8
            """;
        const string variant = """
            #EXTM3U
            #EXT-X-TARGETDURATION:4
            #EXTINF:4,
            segment.ts
            #EXT-X-ENDLIST
            """;
        Uri redirected = new("https://cdn.example.test/final/master.m3u8");
        using HttpClient client = new(new RoutingHandler(request =>
        {
            if (request.RequestUri!.Host.Equals("entry.example.test", StringComparison.Ordinal))
            {
                HttpResponseMessage response = RoutingHandler.Text(master, "application/vnd.apple.mpegurl");
                response.RequestMessage = new HttpRequestMessage(HttpMethod.Get, redirected);
                return response;
            }

            if (request.RequestUri.AbsoluteUri.Equals("https://cdn.example.test/final/video/720.m3u8", StringComparison.Ordinal))
            {
                return RoutingHandler.Text(variant, "application/vnd.apple.mpegurl");
            }

            return new HttpResponseMessage(HttpStatusCode.NotFound);
        }));
        MediaCatalogService service = new(client, new NullYtDlpProvider());

        MediaCatalog catalog = await service.GetCatalogAsync(new Uri("https://entry.example.test/start/master.m3u8"));

        MediaFormat video = Assert.Single(catalog.VideoFormats);
        Assert.Equal("https://cdn.example.test/final/video/720.m3u8", video.ManifestUri.AbsoluteUri);
        Assert.False(catalog.IsLive);
        Assert.Equal(new Uri("https://entry.example.test/start/master.m3u8"), catalog.Source);
    }

    [Fact]
    public async Task DashCatalogPreservesScopedRepresentationIdentityDrmRolesAndDuration()
    {
        const string mpd = """
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT20S">
              <Period id="intro" duration="PT10S">
                <AdaptationSet id="video" mimeType="video/mp4" contentType="video" codecs="avc1.42E01E">
                  <Role schemeIdUri="urn:mpeg:dash:role:2011" value="main" />
                  <ContentProtection schemeIdUri="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed" />
                  <Representation id="same" bandwidth="800000" width="640" height="360">
                    <BaseURL>intro-360.mp4</BaseURL>
                  </Representation>
                </AdaptationSet>
              </Period>
              <Period id="main" duration="PT10S">
                <AdaptationSet id="video" mimeType="video/mp4" contentType="video" codecs="avc1.42E01E">
                  <Role schemeIdUri="urn:mpeg:dash:role:2011" value="commentary" />
                  <Representation id="same" bandwidth="1600000" width="1280" height="720">
                    <BaseURL>main-720.mp4</BaseURL>
                  </Representation>
                </AdaptationSet>
              </Period>
            </MPD>
            """;
        using HttpClient client = new(new RoutingHandler(_ => RoutingHandler.Text(mpd, "application/dash+xml")));
        MediaCatalogService service = new(client, new NullYtDlpProvider());

        MediaCatalog catalog = await service.GetCatalogAsync(new Uri("https://media.example.test/path/manifest.mpd"));

        Assert.Equal(TimeSpan.FromSeconds(20), catalog.Duration);
        Assert.Equal(2, catalog.VideoFormats.Count);
        Assert.Equal(2, catalog.VideoFormats.Select(static format => format.Id).Distinct(StringComparer.Ordinal).Count());
        Assert.Equal(2, catalog.VideoFormats.Select(static format => format.ProviderData ?? string.Empty).Distinct(StringComparer.Ordinal).Count());
        MediaFormat intro = catalog.VideoFormats.Single(static format => format.PeriodId == "intro");
        MediaFormat main = catalog.VideoFormats.Single(static format => format.PeriodId == "main");
        Assert.True(intro.IsEncrypted);
        Assert.True(intro.IsDefault);
        Assert.Equal("video", intro.AdaptationSetId);
        Assert.Equal("main", intro.Role);
        Assert.False(main.IsEncrypted);
        Assert.Equal("commentary", main.Role);
    }

    [Fact]
    public async Task ProviderFallbackRunsWhenHttpProbeFailsBeforeParsing()
    {
        MediaCatalog expected = new(
            new Uri("https://site.example.test/watch"),
            MediaKind.ExternalProvider,
            "Provider result",
            false,
            [new MediaFormat("provider", MediaStreamKind.Muxed, new Uri("https://cdn.example.test/file.mp4"), "mp4", null, null, null, null, null, null, null, true, false)],
            "provider",
            "yt-dlp");
        FixedYtDlpProvider provider = new(expected);
        using HttpClient client = new(new RoutingHandler(_ => new HttpResponseMessage(HttpStatusCode.Forbidden)));
        MediaCatalogService service = new(client, provider);

        MediaCatalog actual = await service.GetCatalogAsync(expected.Source);

        Assert.Same(expected, actual);
        Assert.Equal(1, provider.Calls);
    }

    [Fact]
    public async Task DirectMediaUsesFilenameEvidenceAndClassifiesAudioTruthfully()
    {
        using HttpClient client = new(new RoutingHandler(_ =>
        {
            HttpResponseMessage response = RoutingHandler.Bytes([1, 2, 3]);
            response.Content.Headers.ContentType = MediaTypeHeaderValue.Parse("application/octet-stream");
            response.Content.Headers.ContentDisposition = ContentDispositionHeaderValue.Parse("attachment; filename=\"song.flac\"");
            return response;
        }));
        MediaCatalogService service = new(client, new NullYtDlpProvider());

        MediaCatalog catalog = await service.GetCatalogAsync(new Uri("https://download.example.test/file?id=123"));

        MediaFormat format = Assert.Single(catalog.Formats);
        Assert.Equal(MediaKind.DirectFile, catalog.Kind);
        Assert.Equal(MediaStreamKind.Audio, format.StreamKind);
        Assert.Equal("flac", format.Container);
        Assert.Equal("song.flac", catalog.Title);
    }

    [Fact]
    public void SelectionUsesHardQualityCeilingAndHlsGroupAssociation()
    {
        Uri source = new("https://media.example.test/master.m3u8");
        MediaCatalog catalog = new(
            source,
            MediaKind.Hls,
            "Grouped",
            false,
            [
                Format("video-en", MediaStreamKind.Video, height: 720, bandwidth: 2_000_000, isDefault: true, audioGroupId: "aud-en", subtitleGroupId: "sub-en"),
                Format("video-pt", MediaStreamKind.Video, height: 480, bandwidth: 1_000_000, audioGroupId: "aud-pt", subtitleGroupId: "sub-pt"),
                Format("audio-en", MediaStreamKind.Audio, language: "en", bandwidth: 128_000, audioGroupId: "aud-en"),
                Format("audio-pt", MediaStreamKind.Audio, language: "pt-BR", bandwidth: 128_000, audioGroupId: "aud-pt"),
                Format("sub-en", MediaStreamKind.Subtitle, language: "en", isDefault: true, subtitleGroupId: "sub-en"),
                Format("sub-pt", MediaStreamKind.Subtitle, language: "pt-BR", isDefault: true, subtitleGroupId: "sub-pt")
            ],
            "test",
            "native-hls");

        MediaSelectionResult result = MediaSelectionPolicy.Select(
            catalog,
            new MediaSelectionRequest(480, AudioLanguage: "en", SubtitleLanguage: "Default"));

        Assert.Equal("video-pt", result.Video?.Id);
        Assert.Equal("audio-pt", result.Audio?.Id);
        Assert.Equal("sub-pt", Assert.Single(result.Subtitles).Id);

        MediaSelectionResult noneWithinCeiling = MediaSelectionPolicy.Select(
            catalog with { Formats = [Format("video-720", MediaStreamKind.Video, height: 720, bandwidth: 2_000_000)] },
            new MediaSelectionRequest(480));
        Assert.Null(noneWithinCeiling.Video);
    }

    [Fact]
    public void YtDlpCatalogUsesAbrVbrStableSubtitleIdsAndRejectsCodeclessEntries()
    {
        const string json = """
            {
              "title": "Provider page",
              "duration": 60,
              "formats": [
                {"format_id":"audio","url":"https://cdn.example.test/audio.m4a","ext":"m4a","vcodec":"none","acodec":"mp4a.40.2","abr":128},
                {"format_id":"video","url":"https://cdn.example.test/video.mp4","ext":"mp4","vcodec":"avc1.640028","acodec":"none","height":1080,"vbr":2500},
                {"format_id":"metadata","url":"https://cdn.example.test/metadata.bin","ext":"bin","vcodec":"none","acodec":"none"}
              ],
              "subtitles": {
                "en": [{"url":"https://cdn.example.test/subtitles/en.vtt","ext":"vtt","name":"English"}]
              }
            }
            """;

        MediaCatalog catalog = YtDlpProvider.ParseCatalog(new Uri("https://site.example.test/watch"), json);

        Assert.DoesNotContain(catalog.Formats, static format => format.Id == "metadata");
        Assert.Equal(128_000, catalog.AudioFormats.Single().Bandwidth);
        Assert.Equal(2_500_000, catalog.VideoFormats.Single().Bandwidth);
        MediaFormat subtitle = Assert.Single(catalog.SubtitleFormats);
        Assert.StartsWith("subtitle-en-", subtitle.Id, StringComparison.Ordinal);
        Assert.NotEqual("subtitle-en-0", subtitle.Id);
    }

    [Fact]
    public async Task YtDlpProviderPassesProxyPolicyThroughPrivateConfigFile()
    {
        const string json = """
            {
              "title": "Proxy page",
              "formats": [
                {"format_id":"audio","url":"https://cdn.example.test/audio.m4a","ext":"m4a","vcodec":"none","acodec":"mp4a.40.2","abr":128}
              ]
            }
            """;
        RecordingExternalToolRunner runner = new(new ExternalToolResult(0, json, string.Empty));
        StaticYtDlpNetworkPolicyProvider policy = new(new YtDlpNetworkPolicy("http://proxy.example.test:8080", false));
        YtDlpProvider provider = new(runner, policy, "/usr/bin/yt-dlp");

        MediaCatalog? catalog = await provider.TryGetCatalogAsync(
            new Uri("https://site.example.test/watch"),
            new MediaRequestMetadata(UserAgent: "XDM-Test", Referer: "https://site.example.test/"));

        Assert.NotNull(catalog);
        string configText = runner.ConfigText ?? string.Empty;
        Assert.Contains("--proxy \"http://proxy.example.test:8080\"", configText, StringComparison.Ordinal);
        Assert.Contains("--user-agent \"XDM-Test\"", configText, StringComparison.Ordinal);
        Assert.Contains("--referer \"https://site.example.test/\"", configText, StringComparison.Ordinal);
        Assert.Contains("--config-locations", runner.LastArguments);
    }

    [Fact]
    public async Task YtDlpProviderContainsToolFailuresAsDiagnosticCatalogs()
    {
        RecordingExternalToolRunner runner = new(new ExternalToolResult(1, string.Empty, "ERROR: extractor failed\nwith detail"));
        YtDlpProvider provider = new(runner, StaticYtDlpNetworkPolicyProvider.SystemDefault, "/usr/bin/yt-dlp");

        MediaCatalog? catalog = await provider.TryGetCatalogAsync(
            new Uri("https://site.example.test/watch"),
            MediaRequestMetadata.Empty);

        Assert.NotNull(catalog);
        Assert.Equal(MediaKind.Unknown, catalog!.Kind);
        Assert.Contains("yt-dlp could not extract this URL", catalog.Description, StringComparison.Ordinal);
        Assert.Contains("ERROR: extractor failed with detail", catalog.Description, StringComparison.Ordinal);
    }

    private static MediaFormat Format(
        string id,
        MediaStreamKind kind,
        int? height = null,
        long? bandwidth = null,
        string? language = null,
        bool isDefault = false,
        string? audioGroupId = null,
        string? subtitleGroupId = null)
        => new(
            id,
            kind,
            new Uri($"https://media.example.test/{id}"),
            kind == MediaStreamKind.Subtitle ? "vtt" : "mp4",
            null,
            bandwidth,
            height is null ? null : height * 16 / 9,
            height,
            null,
            language,
            id,
            isDefault,
            false,
            null,
            audioGroupId,
            subtitleGroupId);

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
        public int Calls { get; private set; }

        public Task<ExternalToolHealth> GetHealthAsync(CancellationToken cancellationToken = default)
            => Task.FromResult(new ExternalToolHealth("yt-dlp", true, "test", "test", "ok"));

        public Task<MediaCatalog?> TryGetCatalogAsync(
            Uri source,
            MediaRequestMetadata metadata,
            CancellationToken cancellationToken = default)
        {
            Calls++;
            return Task.FromResult<MediaCatalog?>(catalog);
        }
    }

    private sealed class RecordingExternalToolRunner(ExternalToolResult result) : IExternalToolRunner
    {
        public string? ConfigText { get; private set; }

        public IReadOnlyList<string> LastArguments { get; private set; } = [];

        public Task<ExternalToolResult> RunAsync(
            string executablePath,
            IReadOnlyList<string> arguments,
            TimeSpan timeout,
            int maximumOutputBytes,
            CancellationToken cancellationToken = default)
        {
            LastArguments = arguments.ToArray();
            string[] array = LastArguments.ToArray();
            int configIndex = Array.IndexOf(array, "--config-locations");
            if (configIndex >= 0 && configIndex + 1 < array.Length)
            {
                ConfigText = File.ReadAllText(array[configIndex + 1]);
            }

            return Task.FromResult(result);
        }
    }
}
