using XDM.Diagnostics;

namespace XDM.Diagnostics.Tests;

public sealed class SecretRedactorTests
{
    [Fact]
    public void RedactRemovesHeaderAndQuerySecrets()
    {
        string input = "Authorization: Bearer abc123 https://host/file?token=secret&name=safe Cookie=session-value";
        string value = SecretRedactor.Redact(input);

        Assert.DoesNotContain("abc123", value, StringComparison.Ordinal);
        Assert.DoesNotContain("secret", value, StringComparison.Ordinal);
        Assert.DoesNotContain("session-value", value, StringComparison.Ordinal);
        Assert.Contains("name=safe", value, StringComparison.Ordinal);
    }
}
