using XDM.Media;

namespace XDM.BrowserMedia.Tests;

public sealed class FfmpegCapabilitiesTests
{
    [Fact]
    public async Task RetainsBoundedDiagnosticTailInsteadOfThrowing()
    {
        byte[] payload = System.Text.Encoding.UTF8.GetBytes(new string('a', 4096) + "tail-marker");
        using MemoryStream stream = new(payload);
        using StreamReader reader = new(stream, System.Text.Encoding.UTF8);

        string retained = await BoundedTextCapture.ReadRetainedAsync(reader, 1024);

        Assert.Contains(BoundedTextCapture.TruncationNotice, retained, StringComparison.Ordinal);
        Assert.Contains("tail-marker", retained, StringComparison.Ordinal);
        Assert.DoesNotContain(new string('a', 2048), retained, StringComparison.Ordinal);
    }

    [Fact]
    public void CalculatesMuxTimeoutFromInputSize()
    {
        string directory = Path.Combine(Path.GetTempPath(), $"xdm-mux-timeout-{Guid.NewGuid():N}");
        Directory.CreateDirectory(directory);
        string input = Path.Combine(directory, "input.bin");
        try
        {
            File.WriteAllBytes(input, new byte[1024 * 1024]);

            TimeSpan timeout = FfmpegService.CalculateMuxTimeout([input]);

            Assert.InRange(timeout, TimeSpan.FromMinutes(5), TimeSpan.FromMinutes(6));
        }
        finally
        {
            Directory.Delete(directory, recursive: true);
        }
    }

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
