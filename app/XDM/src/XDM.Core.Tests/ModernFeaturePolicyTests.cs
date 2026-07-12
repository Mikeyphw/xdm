using XDM.Core.Product;

namespace XDM.Core.Tests;

public sealed class ModernFeaturePolicyTests
{
    [Theory]
    [InlineData("https://example.test/file.zip", true)]
    [InlineData("http://example.test/file.zip", true)]
    [InlineData("ftp://example.test/file.zip", true)]
    [InlineData("ftps://example.test/file.zip", true)]
    [InlineData("file:///tmp/file.zip", false)]
    public void SupportsOriginalDownloadSchemes(string value, bool expected)
        => Assert.Equal(expected, ModernFeaturePolicy.IsSupportedDownloadUri(new Uri(value)));

    [Fact]
    public void UnsupportedSchemeMessageListsSupportedProtocols()
    {
        string message = ModernFeaturePolicy.GetUnsupportedDownloadMessage(
            new Uri("file:///tmp/file.zip"));

        Assert.Contains("HTTP", message, StringComparison.Ordinal);
        Assert.Contains("FTP", message, StringComparison.Ordinal);
    }

    [Fact]
    public void UpdatesUseInApplicationVerifiedPackageChannel()
    {
        Assert.Equal(UpdateDeliveryMode.InApplicationVerifiedPackage, ModernFeaturePolicy.UpdateDelivery);
        Assert.Equal(Uri.UriSchemeHttps, ModernFeaturePolicy.UpdateManifest.Scheme);
        Assert.Equal("github.com", ModernFeaturePolicy.UpdateManifest.Host);
    }
}
