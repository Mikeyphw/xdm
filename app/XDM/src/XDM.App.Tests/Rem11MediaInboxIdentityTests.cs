using XDM.App.ViewModels;
using XDM.Media;

namespace XDM.App.Tests;

public sealed class Rem11MediaInboxIdentityTests
{
    [Fact]
    public void MediaInboxIdentityIncludesAuthenticatedRequestContext()
    {
        Uri source = new("https://media.example.test/video.m3u8");
        MediaCatalog catalog = new(
            source,
            MediaKind.Hls,
            "Video",
            false,
            [new MediaFormat("v", MediaStreamKind.Muxed, source, "hls", null, null, null, null, null, null, null, true, false)],
            "test",
            "native-hls");

        MediaInboxItemViewModel first = new(
            catalog,
            new MediaRequestMetadata(Cookie: "session=one", Referer: "https://site.example.test/a"),
            "https://site.example.test/watch",
            "Firefox",
            DateTimeOffset.UnixEpoch);
        MediaInboxItemViewModel second = new(
            catalog,
            new MediaRequestMetadata(Cookie: "session=two", Referer: "https://site.example.test/a"),
            "https://site.example.test/watch",
            "Firefox",
            DateTimeOffset.UnixEpoch);

        Assert.NotEqual(first.Id, second.Id);
    }
}
