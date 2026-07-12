using XDM.Core.Product;

namespace XDM.Core.Tests;

public sealed class ModernFeaturePolicyTests
{
    [Theory]
    [InlineData("https://example.test/file.zip", true)]
    [InlineData("http://example.test/file.zip", true)]
    [InlineData("ftp://example.test/file.zip", false)]
    [InlineData("ftps://example.test/file.zip", false)]
    public void SupportsOnlyModernHttpDownloadSchemes(string value, bool expected)
        => Assert.Equal(expected, ModernFeaturePolicy.IsSupportedDownloadUri(new Uri(value)));

    [Fact]
    public void FtpReplacementIsExplicitAndActionable()
    {
        string message = ModernFeaturePolicy.GetUnsupportedDownloadMessage(
            new Uri("ftp://example.test/file.zip"));

        Assert.Contains("intentionally not handled", message, StringComparison.Ordinal);
        Assert.Contains("HTTPS", message, StringComparison.Ordinal);
    }

    [Fact]
    public void UpdatesUseExternalSignedPackageChannel()
    {
        Assert.Equal(UpdateDeliveryMode.ExternalSignedPackage, ModernFeaturePolicy.UpdateDelivery);
        Assert.Equal(Uri.UriSchemeHttps, ModernFeaturePolicy.ReleasePage.Scheme);
        Assert.Equal("github.com", ModernFeaturePolicy.ReleasePage.Host);
    }
}
