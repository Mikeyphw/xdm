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


    [Fact]
    public void SegmentListIdsRemainStableWhenSlidingListPrependsNewUris()
    {
        const string first = """
            <MPD mediaPresentationDuration="PT20S"><Period><AdaptationSet mimeType="video/mp4"><Representation id="v1">
              <SegmentList><SegmentURL media="a.m4s" /><SegmentURL media="b.m4s" /></SegmentList>
            </Representation></AdaptationSet></Period></MPD>
            """;
        const string second = """
            <MPD mediaPresentationDuration="PT30S"><Period><AdaptationSet mimeType="video/mp4"><Representation id="v1">
              <SegmentList><SegmentURL media="new.m4s" /><SegmentURL media="a.m4s" /><SegmentURL media="b.m4s" /></SegmentList>
            </Representation></AdaptationSet></Period></MPD>
            """;
        Uri source = new("https://media.example.test/path/manifest.mpd");
        DashSegmentReference firstA = DashManifestParser.BuildSegments(
            DashManifestParser.Parse(source, first).Representations[0],
            DashManifestParser.Parse(source, first),
            DateTimeOffset.UnixEpoch)[0];
        DashManifest secondManifest = DashManifestParser.Parse(source, second);
        DashSegmentReference secondA = DashManifestParser.BuildSegments(secondManifest.Representations[0], secondManifest, DateTimeOffset.UnixEpoch)[1];

        Assert.Equal(firstA.Id, secondA.Id);
    }

    [Fact]
    public void DynamicOpenTimelineExpandsWithinLiveWindow()
    {
        const string manifestText = """
            <MPD type="dynamic" availabilityStartTime="2026-09-13T00:00:00Z" timeShiftBufferDepth="PT30S" minimumUpdatePeriod="PT1S">
              <Period><AdaptationSet mimeType="video/mp4"><Representation id="v1">
                <BaseURL>https://media.example.test/live/</BaseURL>
                <SegmentTemplate timescale="1" media="s-$Time$.m4s">
                  <SegmentTimeline><S t="0" d="5" r="-1" /></SegmentTimeline>
                </SegmentTemplate>
              </Representation></AdaptationSet></Period>
            </MPD>
            """;
        Uri source = new("https://media.example.test/live/manifest.mpd");
        DashManifest manifest = DashManifestParser.Parse(source, manifestText);

        List<DashSegmentReference> segments = DashManifestParser.BuildSegments(
            manifest.Representations[0],
            manifest,
            new DateTimeOffset(2026, 9, 13, 0, 1, 0, TimeSpan.Zero));

        Assert.True(segments.Count > 1);
        Assert.Contains(segments, static segment => segment.Uri.AbsoluteUri.EndsWith("s-55.m4s", StringComparison.Ordinal));
    }

}
