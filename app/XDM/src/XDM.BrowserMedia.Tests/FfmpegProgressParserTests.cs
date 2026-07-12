using XDM.Media;

namespace XDM.BrowserMedia.Tests;

public sealed class FfmpegProgressParserTests
{
    [Theory]
    [InlineData("out_time_us", "2500000", 2.5)]
    [InlineData("out_time_ms", "1000000", 1.0)]
    [InlineData("out_time", "00:00:03.250000", 3.25)]
    public void ParsesStructuredProgressTime(string key, string value, double expectedSeconds)
    {
        bool parsed = FfmpegConversionProcessRunner.TryParseProgressTime(key, value, out TimeSpan processed);

        Assert.True(parsed);
        Assert.Equal(expectedSeconds, processed.TotalSeconds, 3);
    }
}
