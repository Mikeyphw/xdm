using XDM.Core.Settings;

namespace XDM.Core.Tests;

public sealed class Aria2IntegrationSettingsTests
{
    [Fact]
    public void NormalizeClampsOperationalValuesAndRepairsManagedEndpoint()
    {
        Aria2IntegrationSettings settings = Aria2IntegrationSettings.Default with
        {
            Enabled = true,
            ConnectionMode = Aria2ConnectionMode.ManagedProcess,
            RpcEndpoint = "http://downloads.example.test:9900",
            PollIntervalMilliseconds = 1,
            RpcConnectTimeoutSeconds = 500,
            MaxConcurrentDownloads = 0,
            SplitCount = 500,
            MinimumSplitSizeBytes = 1
        };

        Aria2IntegrationSettings normalized = settings.Normalize();

        Assert.True(new Uri(normalized.RpcEndpoint).IsLoopback);
        Assert.Equal(250, normalized.PollIntervalMilliseconds);
        Assert.Equal(120, normalized.RpcConnectTimeoutSeconds);
        Assert.Equal(1, normalized.MaxConcurrentDownloads);
        Assert.Equal(64, normalized.SplitCount);
        Assert.Equal(1024 * 1024, normalized.MinimumSplitSizeBytes);
    }

    [Fact]
    public void ApplicationSettingsNormalizeAddsAria2DefaultsAndPreservesExplicitConfiguration()
    {
        ApplicationSettings defaults = ApplicationSettings.CreateDefault();
        Assert.NotNull(defaults.Aria2);
        Assert.False(defaults.Aria2!.Enabled);

        ApplicationSettings configured = (defaults with
        {
            Aria2 = defaults.Aria2 with
            {
                Enabled = true,
                ConnectionMode = Aria2ConnectionMode.ExternalRpc,
                RpcEndpoint = "https://aria2.example.test/jsonrpc",
                RpcSecret = "secret"
            }
        }).Normalize();

        Assert.True(configured.Aria2!.Enabled);
        Assert.Equal(Aria2ConnectionMode.ExternalRpc, configured.Aria2.ConnectionMode);
        Assert.Equal("https://aria2.example.test/jsonrpc", configured.Aria2.RpcEndpoint);
        Assert.Equal("secret", configured.Aria2.RpcSecret);
    }
}
