using XDM.Core.Settings;

namespace XDM.Core.Tests;

public sealed class NetworkSettingsTests
{
    [Fact]
    public void NormalizationBoundsTimeoutRetryAndConnectionValues()
    {
        NetworkSettings settings = new(
            0,
            -1,
            99,
            1,
            32,
            2,
            1,
            new ProxySettings(ProxyMode.Manual, " ", 70000, null, null, true, []));

        NetworkSettings normalized = settings.Normalize();

        Assert.Equal(1, normalized.ConnectTimeoutSeconds);
        Assert.Equal(0, normalized.RequestTimeoutSeconds);
        Assert.Equal(20, normalized.MaximumRetryAttempts);
        Assert.Equal(100, normalized.RetryBaseDelayMilliseconds);
        Assert.Equal(2, normalized.DefaultConnectionCount);
        Assert.Equal(2, normalized.MaximumConnectionCount);
        Assert.Equal(64L * 1024, normalized.MinimumSegmentedSizeBytes);
        Assert.Equal(ProxyMode.Manual, normalized.Proxy!.Mode);
    }

    [Theory]
    [InlineData("example.com", true, true)]
    [InlineData("cdn.example.com", true, true)]
    [InlineData("cdn.example.com", false, false)]
    [InlineData("notexample.com", true, false)]
    public void ServerCredentialsMatchExactHostsAndOptionalSubdomains(
        string host,
        bool includeSubdomains,
        bool expected)
    {
        ServerCredentialDefinition credential = new("example.com", "user", "secret", includeSubdomains);

        Assert.Equal(expected, credential.Matches(new Uri($"https://{host}/file.bin")));
    }
    [Fact]
    public void ConfiguredClientUsesBoundedRequestTimeout()
    {
        ApplicationSettings settings = ApplicationSettings.CreateDefault() with
        {
            Network = NetworkSettings.Default with
            {
                RequestTimeoutSeconds = 75,
                Proxy = ProxySettings.SystemDefault with { Mode = ProxyMode.None }
            }
        };

        using HttpClient client = XDM.Platform.ConfiguredHttpClientFactory.Create(settings);

        Assert.Equal(TimeSpan.FromSeconds(75), client.Timeout);
    }

    [Fact]
    public void SavedCredentialResolverBindsCredentialsToMatchingHostOnly()
    {
        ApplicationSettings settings = ApplicationSettings.CreateDefault() with
        {
            Credentials = [new ServerCredentialDefinition("origin.example", "alice", "secret", false)]
        };

        Assert.Equal(("alice", "secret"), ServerCredentialResolver.Resolve(settings, new Uri("https://origin.example/file")));
        Assert.Equal<(string? Username, string? Password)>(
            (null, null),
            ServerCredentialResolver.Resolve(settings, new Uri("https://mirror.example/file")));
    }

    [Fact]
    public void CredentialOriginComparisonIncludesSchemeAndPort()
    {
        Assert.True(ServerCredentialResolver.IsSameOrigin(
            new Uri("https://example.test/a"),
            new Uri("https://example.test/b")));
        Assert.False(ServerCredentialResolver.IsSameOrigin(
            new Uri("https://example.test/a"),
            new Uri("http://example.test/a")));
        Assert.False(ServerCredentialResolver.IsSameOrigin(
            new Uri("https://example.test:8443/a"),
            new Uri("https://example.test/a")));
    }

}
