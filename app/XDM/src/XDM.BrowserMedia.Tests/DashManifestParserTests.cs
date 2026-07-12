using XDM.Media;

namespace XDM.BrowserMedia.Tests;

public sealed class DashManifestParserTests
{
    [Fact]
    public void ParsesRepresentationsAndExpandsTimelineTemplates()
    {
        Uri source = new("https://media.example.test/path/manifest.mpd");
        DashManifest manifest = DashManifestParser.Parse(source, MediaFixture.Read("dash-static.mpd"));

        Assert.False(manifest.IsDynamic);
        Assert.Equal(2, manifest.Representations.Count);
        DashRepresentation video = Assert.Single(manifest.Representations, static item => item.StreamKind == MediaStreamKind.Video);
        List<DashSegmentReference> segments = DashManifestParser.BuildSegments(video, manifest, DateTimeOffset.UnixEpoch);
        Assert.Equal(4, segments.Count);
        Assert.Equal("https://media.example.test/path/init-v1080.mp4", segments[0].Uri.AbsoluteUri);
        Assert.Equal("https://media.example.test/path/chunk-v1080-00003.m4s", segments[^1].Uri.AbsoluteUri);
    }

    [Fact]
    public void ParsesSegmentListAudio()
    {
        Uri source = new("https://media.example.test/path/manifest.mpd");
        DashManifest manifest = DashManifestParser.Parse(source, MediaFixture.Read("dash-static.mpd"));
        DashRepresentation audio = Assert.Single(manifest.Representations, static item => item.StreamKind == MediaStreamKind.Audio);
        List<DashSegmentReference> segments = DashManifestParser.BuildSegments(audio, manifest, DateTimeOffset.UnixEpoch);

        Assert.Equal(3, segments.Count);
        Assert.True(segments[0].IsInitialization);
        Assert.Equal("https://media.example.test/path/audio-2.m4s", segments[^1].Uri.AbsoluteUri);
    }

    [Fact]
    public void RejectsXmlWithDocumentType()
    {
        const string malicious = "<!DOCTYPE MPD [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]><MPD>&xxe;</MPD>";
        Assert.Throws<InvalidDataException>(() =>
            DashManifestParser.Parse(new Uri("https://example.test/manifest.mpd"), malicious));
    }
}
