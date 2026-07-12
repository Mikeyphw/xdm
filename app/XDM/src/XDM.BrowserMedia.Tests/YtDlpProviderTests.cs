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
    public void RejectsCatalogWithoutUsableHttpFormats()
    {
        const string json = "{\"title\":\"bad\",\"formats\":[{\"format_id\":\"x\",\"url\":\"file:///tmp/a\"}]}";
        Assert.Throws<InvalidDataException>(() =>
            YtDlpProvider.ParseCatalog(new Uri("https://example.test/watch"), json));
    }
}
