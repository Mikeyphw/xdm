using XDM.Media;

namespace XDM.BrowserMedia.Tests;

public sealed class FfprobeMediaInspectionTests
{
    [Fact]
    public void ParsesDurationContainerAndFirstAudioVideoCodecs()
    {
        const string json = """
            {
              "streams": [
                { "codec_name": "h264", "codec_type": "video" },
                { "codec_name": "aac", "codec_type": "audio" }
              ],
              "format": { "format_name": "mov,mp4,m4a,3gp,3g2,mj2", "duration": "12.500000" }
            }
            """;

        MediaInspection inspection = FfprobeMediaInspectionService.Parse(json);

        Assert.True(inspection.HasVideo);
        Assert.True(inspection.HasAudio);
        Assert.Equal("h264", inspection.VideoCodec);
        Assert.Equal("aac", inspection.AudioCodec);
        Assert.Equal(TimeSpan.FromSeconds(12.5), inspection.Duration);
        Assert.NotNull(inspection.FormatName);
        Assert.Contains("mp4", inspection.FormatName!, StringComparison.Ordinal);
    }
}
