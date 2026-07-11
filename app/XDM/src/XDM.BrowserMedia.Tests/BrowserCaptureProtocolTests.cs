using System.Text;
using XDM.BrowserIntegration;

namespace XDM.BrowserMedia.Tests;

public sealed class BrowserCaptureProtocolTests
{
    [Fact]
    public void ParsesAndNormalizesCaptureRequest()
    {
        byte[] payload = Encoding.UTF8.GetBytes("""
            {
              "url": "https://example.test/video.m3u8",
              "fileName": "  video.ts  ",
              "browser": "Firefox",
              "headers": { " X-Test ": " value " }
            }
            """);

        BrowserCaptureRequest request = BrowserCaptureProtocol.Parse(payload);

        Assert.Equal(new Uri("https://example.test/video.m3u8"), request.Url);
        Assert.Equal("video.ts", request.FileName);
        Assert.Equal("value", request.Headers!["X-Test"]);
        Assert.Equal("Firefox", request.Browser);
    }

    [Fact]
    public void RejectsUnsupportedSchemes()
    {
        byte[] payload = Encoding.UTF8.GetBytes("{\"url\":\"file:///tmp/test.bin\"}");

        Assert.Throws<InvalidDataException>(() => BrowserCaptureProtocol.Parse(payload));
    }
}
