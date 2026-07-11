using System.Text;
using System.Text.Json;
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
              "headers": { " Accept-Language ": " en-US " }
            }
            """);

        BrowserCaptureRequest request = BrowserCaptureProtocol.Parse(payload);

        Assert.Equal(new Uri("https://example.test/video.m3u8"), request.Url);
        Assert.Equal("video.ts", request.FileName);
        Assert.Equal("en-US", request.Headers!["Accept-Language"]);
        Assert.Equal("Firefox", request.Browser);
    }

    [Fact]
    public void RoundTripsPostMetadataAndRejectsUnknownFields()
    {
        BrowserCaptureRequest expected = new(
            new Uri("https://example.test/export"),
            RequestId: "roundtrip",
            Method: "POST",
            RequestBodyBase64: Convert.ToBase64String(Encoding.UTF8.GetBytes("a=b")),
            RequestBodyContentType: "application/x-www-form-urlencoded");

        BrowserCaptureRequest actual = BrowserCaptureProtocol.Parse(BrowserCaptureProtocol.Serialize(expected));
        byte[] unknown = Encoding.UTF8.GetBytes("{\"url\":\"https://example.test/file\",\"unknown\":true}");

        Assert.Equal("a=b", Encoding.UTF8.GetString(actual.GetRequestBody()!));
        Assert.Throws<JsonException>(() => BrowserCaptureProtocol.Parse(unknown));
    }

    [Fact]
    public void RejectsOversizedPayloadAndUnsafeHeaders()
    {
        byte[] oversized = new byte[BrowserCaptureProtocol.MaximumPayloadBytes + 1];
        byte[] forbiddenHeader = Encoding.UTF8.GetBytes("""
            {"url":"https://example.test/file","headers":{"Host":"evil.test"}}
            """);
        byte[] sensitiveHeader = Encoding.UTF8.GetBytes("""
            {"url":"https://example.test/file","headers":{"Authorization":"Bearer secret"}}
            """);

        Assert.Throws<InvalidDataException>(() => BrowserCaptureProtocol.Parse(oversized));
        Assert.Throws<InvalidDataException>(() => BrowserCaptureProtocol.Parse(forbiddenHeader));
        Assert.Throws<InvalidDataException>(() => BrowserCaptureProtocol.Parse(sensitiveHeader));
    }

    [Fact]
    public void RejectsUnsupportedSchemes()
    {
        byte[] payload = Encoding.UTF8.GetBytes("{\"url\":\"file:///tmp/test.bin\"}");

        Assert.Throws<InvalidDataException>(() => BrowserCaptureProtocol.Parse(payload));
    }
}
