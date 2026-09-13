using XDM.Core.Downloads;

namespace XDM.Core.Tests;

public sealed class DownloadInputParserTests
{
    [Fact]
    public void ParsesDistinctSupportedUrlsFromMultilineInput()
    {
        IReadOnlyList<Uri> urls = DownloadInputParser.ParseUrls("""
            https://example.test/a.zip
            invalid
            https://example.test/b.zip https://example.test/a.zip
            ftp://example.test/nope
            """);

        Assert.Equal(3, urls.Count);
        Assert.Equal("https://example.test/a.zip", urls[0].AbsoluteUri);
        Assert.Equal("https://example.test/b.zip", urls[1].AbsoluteUri);
        Assert.Equal("ftp://example.test/nope", urls[2].AbsoluteUri);
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
    [Fact]
    public void KeepsCaseDistinctPathAndQueryIdentities()
    {
        IReadOnlyList<Uri> urls = DownloadInputParser.ParseUrls("https://example.test/File.bin?q=A https://EXAMPLE.test/file.bin?q=a");

        Assert.Equal(2, urls.Count);
    }

    [Fact]
    public void DetailedParsePreservesRejectedTokensForRetry()
    {
        DownloadUrlParseResult result = DownloadInputParser.ParseUrlsDetailed(
            "https://example.test/a.bin malformed gopher://example.test/nope");

        Assert.Single(result.AcceptedUrls);
        Assert.Equal(2, result.RejectedTokens.Count);
        Assert.Contains(result.RejectedTokens, token => token.Input == "malformed");
        Assert.Contains(result.RejectedTokens, token => token.Input.StartsWith("gopher:", StringComparison.Ordinal));
    }

}
