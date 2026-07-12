using System.Text;
using System.Text.Json;
using XDM.BrowserIntegration;

namespace XDM.BrowserMedia.Tests;

public sealed class BrowserNativeProtocolTests
{
    [Fact]
    public void ParsesRecordedHelloFixture()
    {
        BrowserNativeMessage message = ParseFixture("native-hello.json");

        Assert.Equal("2.0", message.ProtocolVersion);
        Assert.Equal("hello", message.Type);
        Assert.Equal("Firefox", message.Client!.Name);
        Assert.Equal("4.0.0", message.Client.ExtensionVersion);
    }

    [Fact]
    public void ParsesRecordedCaptureMetadata()
    {
        BrowserNativeMessage message = ParseFixture("native-capture.json");
        BrowserCaptureRequest capture = message.Capture!;

        Assert.Equal("capture", message.Type);
        Assert.Equal("archive.zip", capture.FileName);
        Assert.Equal("application/zip", capture.Headers!["Accept"]);
        Assert.Equal("session=secret", capture.Cookie);
        Assert.Equal("https://example.test/files", capture.Referer);
        Assert.Equal("Fixture Browser/4.0", capture.UserAgent);
        Assert.Equal("application/zip", capture.MimeType);
        Assert.Equal(8_388_608L, capture.FileSize);
    }

    [Fact]
    public void ParsesRecordedPostCaptureAndDecodesBody()
    {
        BrowserNativeMessage message = ParseFixture("native-post-capture.json");
        BrowserCaptureRequest capture = message.Capture!;

        Assert.Equal("POST", capture.Method);
        Assert.Equal("token=abc&format=zip", Encoding.UTF8.GetString(capture.GetRequestBody()!));
        Assert.Equal("application/x-www-form-urlencoded", capture.RequestBodyContentType);
    }

    [Fact]
    public void ParsesRecordedBatchFixture()
    {
        BrowserNativeMessage message = ParseFixture("native-batch.json");

        Assert.Equal("capture-batch", message.Type);
        Assert.Equal(2, message.Captures!.Count);
        Assert.All(message.Captures, capture => Assert.True(capture.BypassRules));
    }

    [Fact]
    public void RejectsUnknownCommandFixture()
    {
        byte[] payload = File.ReadAllBytes(GetFixturePath("native-unknown-command.json"));

        Assert.Throws<JsonException>(() => BrowserNativeProtocol.Parse(payload));
    }

    [Fact]
    public void RejectsProtocolMismatchAndUnknownProperties()
    {
        byte[] mismatch = Encoding.UTF8.GetBytes("""
            {"protocolVersion":"1.0","requestId":"a","type":"hello","client":{"name":"Firefox"}}
            """);
        byte[] unknown = Encoding.UTF8.GetBytes("""
            {"protocolVersion":"2.0","requestId":"a","type":"hello","client":{"name":"Firefox"},"execute":"whoami"}
            """);

        Assert.Throws<InvalidDataException>(() => BrowserNativeProtocol.Parse(mismatch));
        Assert.Throws<JsonException>(() => BrowserNativeProtocol.Parse(unknown));
    }

    [Fact]
    public void RejectsOversizedMessage()
    {
        byte[] payload = new byte[BrowserNativeProtocol.MaximumMessageBytes + 1];

        Assert.Throws<InvalidDataException>(() => BrowserNativeProtocol.Parse(payload));
    }


    [Fact]
    public void ParsesLeastPrivilegeClientHealthMetadata()
    {
        byte[] payload = Encoding.UTF8.GetBytes("""
            {"protocolVersion":"2.0","requestId":"hello-security","type":"hello","client":{"name":"Chrome","version":"Chrome/140","extensionVersion":"4.1.0","platform":"Linux","extensionId":"abcdefghijklmnopabcdefghijklmnop","manifestVersion":3,"incognitoAllowed":false,"enhancedAccessGranted":false,"grantedOrigins":[]}}
            """);

        BrowserNativeMessage message = BrowserNativeProtocol.Parse(payload);

        Assert.Equal("abcdefghijklmnopabcdefghijklmnop", message.Client!.ExtensionId);
        Assert.Equal(3, message.Client.ManifestVersion);
        Assert.False(message.Client.EnhancedAccessGranted);
        Assert.Equal("compatible", BrowserNativeProtocol.GetCompatibility(message.Client.ExtensionVersion));
    }

    [Theory]
    [InlineData("4.0.9", "extension_outdated")]
    [InlineData("4.1.0", "compatible")]
    [InlineData("5.0.0", "compatible")]
    public void EvaluatesExtensionCompatibility(string version, string expected)
        => Assert.Equal(expected, BrowserNativeProtocol.GetCompatibility(version));

    [Fact]
    public void VerifiesNativeHostLaunchOriginAgainstExtensionIdentity()
    {
        Assert.True(NativeHostOriginVerifier.IsMatch(
            "chrome-extension://abcdefghijklmnopabcdefghijklmnop/",
            "abcdefghijklmnopabcdefghijklmnop"));
        Assert.True(NativeHostOriginVerifier.IsMatch(
            BrowserHostInstaller.FirefoxExtensionId,
            BrowserHostInstaller.FirefoxExtensionId));
        Assert.False(NativeHostOriginVerifier.IsMatch(
            "chrome-extension://abcdefghijklmnopabcdefghijklmnop/",
            "ponmlkjihgfedcbaponmlkjihgfedcba"));
    }

    private static BrowserNativeMessage ParseFixture(string name)
        => BrowserNativeProtocol.Parse(File.ReadAllBytes(GetFixturePath(name)));

    private static string GetFixturePath(string name)
        => Path.Combine(AppContext.BaseDirectory, "Fixtures", name);
}
