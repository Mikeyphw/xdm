using XDM.Core.Downloads;

namespace XDM.Core.Tests;

public sealed class DownloadInputParserTests
{
    [Fact]
    public void ParsesDistinctHttpUrlsFromMultilineInput()
    {
        IReadOnlyList<Uri> urls = DownloadInputParser.ParseUrls("""
            https://example.test/a.zip
            invalid
            https://example.test/b.zip https://example.test/a.zip
            ftp://example.test/nope
            """);

        Assert.Equal(2, urls.Count);
        Assert.Equal("https://example.test/a.zip", urls[0].AbsoluteUri);
        Assert.Equal("https://example.test/b.zip", urls[1].AbsoluteUri);
    }

    [Fact]
    public void ParsesHeaderLinesAndIgnoresMalformedEntries()
    {
        IReadOnlyDictionary<string, string> headers = DownloadInputParser.ParseHeaders("""
            X-Test: one
            malformed
            Accept: application/octet-stream
            """);

        Assert.Equal("one", headers["X-Test"]);
        Assert.Equal("application/octet-stream", headers["Accept"]);
        Assert.Equal(2, headers.Count);
    }
}
