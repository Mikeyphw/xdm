using XDM.Media;

namespace XDM.BrowserMedia.Tests;

public sealed class FfmpegCapabilitiesTests
{
    [Fact]
    public void ParsesCommonEncoderCapabilities()
    {
        ExternalToolHealth health = new("FFmpeg", true, "/usr/bin/ffmpeg", "7.0", "ok");
        const string output = """
             V..... libx264              H.264
             V..... libx265              H.265 / HEVC
             V..... libaom-av1           AV1
             A..... aac                  AAC
             A..... libmp3lame           MP3
             A..... libopus              Opus
            """;

        FfmpegCapabilities capabilities = FfmpegService.ParseCapabilities(health, output);

        Assert.True(capabilities.SupportsH264);
        Assert.True(capabilities.SupportsH265);
        Assert.True(capabilities.SupportsAv1);
        Assert.True(capabilities.SupportsAac);
        Assert.True(capabilities.SupportsMp3);
        Assert.True(capabilities.SupportsOpus);
    }
}
