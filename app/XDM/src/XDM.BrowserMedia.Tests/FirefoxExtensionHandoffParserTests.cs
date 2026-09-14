using System.Text.Json;
using XDM.BrowserIntegration;

namespace XDM.BrowserMedia.Tests;

public sealed class FirefoxExtensionHandoffParserTests
{
    [Fact]
    public void ParsesCanonicalAddV1WithoutChangingItsWireContract()
    {
        string uri = BuildUri("add", new Dictionary<string, string>
        {
            ["v"] = "1",
            ["url"] = "https://cdn.example.test/file.mp4",
            ["page"] = "https://example.test/watch/1",
            ["title"] = "Example video",
            ["filename"] = "example.mp4",
            ["mime"] = "video/mp4"
        });

        Assert.True(FirefoxExtensionHandoffParser.TryParse(uri, out FirefoxExtensionHandoff? handoff, out string error), error);
        BrowserCaptureRequest request = Assert.Single(handoff!.Requests);
        Assert.Equal("https://cdn.example.test/file.mp4", request.Url.AbsoluteUri);
        Assert.Equal("https://example.test/watch/1", request.SourcePage);
        Assert.Equal("example.mp4", request.FileName);
        Assert.Equal("video/mp4", request.MimeType);
        Assert.Equal("context", request.Operation);
        Assert.Equal("Firefox", request.Browser);
        Assert.True(request.BypassRules);
    }

    [Fact]
    public void CaptureV3UsesOnlyFinalSentHeaderAuthority()
    {
        string uri = BuildUri("capture", new Dictionary<string, string>
        {
            ["v"] = "3",
            ["url"] = "https://cdn.example.test/master.m3u8",
            ["page"] = "https://example.test/watch/2",
            ["mime"] = "application/vnd.apple.mpegurl",
            ["proposedHeaders"] = "Cookie: proposed=1\nAuthorization: Bearer proposed",
            ["headers"] = "Cookie: final=1\nReferer: https://example.test/watch/2\nUser-Agent: Firefox-Test\nAccept: */*\nAuthorization: Bearer final\nRange: bytes=0-99",
            ["finalHeaders"] = "Cookie: final=1\nReferer: https://example.test/watch/2\nUser-Agent: Firefox-Test\nAccept: */*\nAuthorization: Bearer final\nRange: bytes=0-99"
        });

        Assert.True(FirefoxExtensionHandoffParser.TryParse(uri, out FirefoxExtensionHandoff? handoff, out string error), error);
        BrowserCaptureRequest request = Assert.Single(handoff!.Requests);
        Assert.Equal("media", request.Operation);
        Assert.Equal("final=1", request.Cookie);
        Assert.Equal("https://example.test/watch/2", request.Referer);
        Assert.Equal("Firefox-Test", request.UserAgent);
        Assert.True(request.Headers!.TryGetValue("Accept", out string? accept));
        Assert.Equal("*/*", accept);
        Assert.DoesNotContain(request.Headers!, pair => pair.Key.Equals("Authorization", StringComparison.OrdinalIgnoreCase));
        Assert.DoesNotContain(request.Headers!, pair => pair.Key.Equals("Range", StringComparison.OrdinalIgnoreCase));
    }

    [Fact]
    public void SessionCandidatesNeverPromoteProposedHeaders()
    {
        string candidates = JsonSerializer.Serialize(new object[]
        {
            new
            {
                url = "https://cdn.example.test/variant-a.m3u8",
                pageUrl = "https://example.test/watch/3",
                stableMediaId = "candidate_001",
                contentType = "application/vnd.apple.mpegurl",
                proposedHeaders = new Dictionary<string, string>
                {
                    ["Cookie"] = "proposed=1",
                    ["Referer"] = "https://evil.example.test/"
                },
                finalHeaders = new Dictionary<string, string>
                {
                    ["Cookie"] = "sent=1",
                    ["Referer"] = "https://example.test/watch/3"
                }
            },
            new
            {
                url = "https://cdn.example.test/variant-b.m3u8",
                pageUrl = "https://example.test/watch/3",
                stableMediaId = "candidate_002",
                contentType = "application/vnd.apple.mpegurl",
                proposedHeaders = new Dictionary<string, string> { ["Cookie"] = "must-not-run=1" }
            }
        });
        string uri = BuildUri("capture", new Dictionary<string, string>
        {
            ["v"] = "3",
            ["url"] = "https://cdn.example.test/master.m3u8",
            ["page"] = "https://example.test/watch/3",
            ["sid"] = "session_12345678",
            ["candidateCount"] = "2",
            ["candidates"] = candidates
        });

        Assert.True(FirefoxExtensionHandoffParser.TryParse(uri, out FirefoxExtensionHandoff? handoff, out string error), error);
        Assert.Equal(3, handoff!.Requests.Count);
        Assert.Equal("sent=1", handoff.Requests[1].Cookie);
        Assert.Equal("https://example.test/watch/3", handoff.Requests[1].Referer);
        Assert.Null(handoff.Requests[2].Cookie);
        Assert.Equal("https://example.test/watch/3", handoff.Requests[2].Referer);
        Assert.Equal("session_12345678", handoff.CaptureSessionId);
        Assert.Equal(2, handoff.DeclaredCandidateCount);
    }

    [Fact]
    public void RejectsCandidateBatchWithoutCaptureSessionIdentity()
    {
        string uri = BuildUri("capture", new Dictionary<string, string>
        {
            ["v"] = "3",
            ["url"] = "https://cdn.example.test/master.m3u8",
            ["candidates"] = "[]"
        });

        Assert.False(FirefoxExtensionHandoffParser.TryParse(uri, out _, out string error));
        Assert.Contains("session ID", error, StringComparison.Ordinal);
    }

    [Theory]
    [InlineData("xdmdownload://capture?v=2&url=https%3A%2F%2Fexample.test%2Fvideo.mp4")]
    [InlineData("xdmdownload://capture?v=3&v=3&url=https%3A%2F%2Fexample.test%2Fvideo.mp4")]
    [InlineData("xdmdownload://capture?v=3&url=ftp%3A%2F%2Fexample.test%2Fvideo.mp4")]
    [InlineData("xdmdownload://capture?v=3&url=https%3A%2F%2Fuser%3Apass%40example.test%2Fvideo.mp4")]
    [InlineData("https://example.test/video.mp4")]
    public void RejectsUnsupportedOrAmbiguousEnvelopes(string uri)
    {
        Assert.False(FirefoxExtensionHandoffParser.TryParse(uri, out _, out string error));
        Assert.NotEmpty(error);
    }

    [Fact]
    public void FindsCanonicalProtocolArgumentAmongNormalDesktopArguments()
    {
        string uri = "xdmdownload://add?v=1&url=https%3A%2F%2Fexample.test%2Ffile.zip";

        Assert.Equal(uri, FirefoxExtensionHandoffParser.FindHandoffArgument(["--minimized", uri, "--other"]));
        Assert.Null(FirefoxExtensionHandoffParser.FindHandoffArgument(["--minimized", "https://example.test"]));
    }

    private static string BuildUri(string action, IReadOnlyDictionary<string, string> parameters)
        => $"xdmdownload://{action}?" + string.Join(
            "&",
            parameters.Select(pair => $"{Uri.EscapeDataString(pair.Key)}={Uri.EscapeDataString(pair.Value)}"));
}
