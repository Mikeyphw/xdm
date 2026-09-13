using XDM.Diagnostics;

namespace XDM.Diagnostics.Tests;

public sealed class SecretRedactorTests
{
    [Fact]
    public void RedactRemovesHeaderAndQuerySecrets()
    {
        string input = string.Join("\n", "Authorization: Bearer abc123", "https://host/file?token=secret&name=safe", "Cookie=session-value");
        string value = SecretRedactor.Redact(input);

        Assert.DoesNotContain("abc123", value, StringComparison.Ordinal);
        Assert.DoesNotContain("secret", value, StringComparison.Ordinal);
        Assert.DoesNotContain("session-value", value, StringComparison.Ordinal);
        Assert.Contains("name=safe", value, StringComparison.Ordinal);
    }

    [Fact]
    public void RedactCoversUserInfoCommonHeadersAndPathSecrets()
    {
        string input = string.Join("\n",
            "Authorization: Basic dXNlcjpwYXNz",
            "Authorization: Digest username=user, realm=test, nonce=secret",
            "Cookie: session=abc; csrftoken=def; foo=bar",
            "https://user:pass@example.test/file?access_token=secret&api_key=hidden&name=safe",
            "/home/mikey/private/downloads/video.mp4");

        string value = SecretRedactor.Redact(input);

        Assert.DoesNotContain("dXNlcjpwYXNz", value, StringComparison.Ordinal);
        Assert.DoesNotContain("nonce=secret", value, StringComparison.Ordinal);
        Assert.DoesNotContain("session=abc", value, StringComparison.Ordinal);
        Assert.DoesNotContain("csrftoken=def", value, StringComparison.Ordinal);
        Assert.DoesNotContain("user:pass", value, StringComparison.Ordinal);
        Assert.DoesNotContain("access_token=secret", value, StringComparison.Ordinal);
        Assert.DoesNotContain("api_key=hidden", value, StringComparison.Ordinal);
        Assert.DoesNotContain("/home/mikey/private", value, StringComparison.Ordinal);
        Assert.Contains("name=safe", value, StringComparison.Ordinal);
    }

    [Fact]
    public void RedactOriginDropsUriUserInfoAndPath()
    {
        string origin = SecretRedactor.RedactOrigin("https://user:pass@example.test:8443/private?token=secret");

        Assert.Equal("https://example.test:8443", origin);
    }
}
