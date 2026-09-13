using System.Text.Json;
using XDM.Media;

namespace XDM.BrowserMedia.Tests;

public sealed class YtDlpProviderTests
{
    [Fact]
    public void ParsesRecordedStructuredCatalog()
    {
        MediaCatalog catalog = YtDlpProvider.ParseCatalog(
            new Uri("https://video.example.test/watch/123"),
            MediaFixture.Read("ytdlp-catalog.json"));

        Assert.Equal(MediaKind.ExternalProvider, catalog.Kind);
        Assert.Equal("Recorded page", catalog.Title);
        Assert.Equal(2, catalog.Formats.Count);
        Assert.Contains(catalog.Formats, static format => format.StreamKind == MediaStreamKind.Video && format.Height == 1080);
        Assert.Contains(catalog.Formats, static format => format.StreamKind == MediaStreamKind.Audio && format.Language == "en");
    }


    [Fact]
    public void ReadsDurationForSizeEstimation()
    {
        const string json = """
            {
              "title": "Timed page",
              "duration": 90.5,
              "formats": [
                {
                  "format_id": "video",
                  "url": "https://cdn.example.test/video.mp4",
                  "ext": "mp4",
                  "vcodec": "h264",
                  "acodec": "aac",
                  "height": 720,
                  "tbr": 2500
                }
              ]
            }
            """;

        MediaCatalog catalog = YtDlpProvider.ParseCatalog(
            new Uri("https://video.example.test/watch/timed"),
            json);

        Assert.Equal(TimeSpan.FromSeconds(90.5), catalog.Duration);
    }

    [Fact]
    public void RejectsCatalogWithoutUsableHttpFormats()
    {
        const string json = "{\"title\":\"bad\",\"formats\":[{\"format_id\":\"x\",\"url\":\"file:///tmp/a\"}]}";
        Assert.Throws<InvalidDataException>(() =>
            YtDlpProvider.ParseCatalog(new Uri("https://example.test/watch"), json));
    }


    [Fact]
    public void FragmentIdsAreStableByUriAcrossProviderRefresh()
    {
        const string first = """
            {
              "title": "page",
              "formats": [
                {
                  "format_id": "video",
                  "url": "https://cdn.example.test/master.mp4",
                  "ext": "mp4",
                  "vcodec": "h264",
                  "acodec": "none",
                  "fragments": [
                    { "url": "https://cdn.example.test/a.m4s" },
                    { "url": "https://cdn.example.test/b.m4s" }
                  ]
                }
              ]
            }
            """;
        const string second = """
            {
              "title": "page",
              "formats": [
                {
                  "format_id": "video",
                  "url": "https://cdn.example.test/master.mp4",
                  "ext": "mp4",
                  "vcodec": "h264",
                  "acodec": "none",
                  "fragments": [
                    { "url": "https://cdn.example.test/new.m4s" },
                    { "url": "https://cdn.example.test/a.m4s" },
                    { "url": "https://cdn.example.test/b.m4s" }
                  ]
                }
              ]
            }
            """;

        ExternalMediaFormatData firstData = JsonSerializer.Deserialize<ExternalMediaFormatData>(
            YtDlpProvider.ParseCatalog(new Uri("https://example.test/watch"), first).Formats[0].ProviderData!)!;
        ExternalMediaFormatData secondData = JsonSerializer.Deserialize<ExternalMediaFormatData>(
            YtDlpProvider.ParseCatalog(new Uri("https://example.test/watch"), second).Formats[0].ProviderData!)!;

        Assert.Equal(firstData.Fragments[0].Id, secondData.Fragments[1].Id);
        Assert.Equal(firstData.Fragments[1].Id, secondData.Fragments[2].Id);
    }

}
