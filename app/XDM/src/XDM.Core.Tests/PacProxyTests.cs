using System.Net;
using XDM.Core.Settings;
using XDM.Platform;

namespace XDM.Core.Tests;

public sealed class PacProxyTests
{
    private const string Script = """
        function FindProxyForURL(url, host) {
          if (isPlainHostName(host)) return "DIRECT";
          if (dnsDomainIs(host, ".internal.example")) return "DIRECT";
          if (shExpMatch(host, "*.media.example")) return "PROXY media-proxy.example:8081";
          return "PROXY proxy.example:3128; DIRECT";
        }
        """;

    [Theory]
    [InlineData("http://printer/status", "http://printer/status")]
    [InlineData("https://app.internal.example/file", "https://app.internal.example/file")]
    [InlineData("https://cdn.media.example/video", "http://media-proxy.example:8081/")]
    [InlineData("https://public.example/file", "http://proxy.example:3128/")]
    public void EvaluatesCommonSafePacRules(string destination, string expectedProxy)
    {
        PacProxy proxy = new(Script);

        Assert.Equal(new Uri(expectedProxy), proxy.GetProxy(new Uri(destination)));
    }

    [Fact]
    public void RejectsOversizedPacScripts()
    {
        string oversized = new('x', 1024 * 1024 + 1);

        Assert.Throws<InvalidDataException>(() => new PacProxy(oversized));
    }

    [Fact]
    public void IntegratedProxyAuthenticationUsesSignedInCredentials()
    {
        ProxySettings proxy = ProxySettings.SystemDefault with
        {
            AuthenticationMode = ProxyAuthenticationMode.Integrated
        };

        Assert.Same(
            CredentialCache.DefaultNetworkCredentials,
            ConfiguredHttpClientFactory.ResolveCredentials(proxy));
    }

    [Fact]
    public void ProxyNormalizationPreservesValidPacAndBoundsAuthentication()
    {
        ProxySettings normalized = new ProxySettings(
            ProxyMode.AutomaticScript,
            null,
            8080,
            null,
            null,
            true,
            [],
            "https://proxy.example/config.pac",
            ProxyAuthenticationMode.Integrated).Normalize();

        Assert.Equal(ProxyMode.AutomaticScript, normalized.Mode);
        Assert.Equal("https://proxy.example/config.pac", normalized.AutomaticConfigurationUrl);
        Assert.Equal(ProxyAuthenticationMode.Integrated, normalized.AuthenticationMode);
    }
}
