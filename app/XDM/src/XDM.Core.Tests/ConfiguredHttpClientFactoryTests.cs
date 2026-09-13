using XDM.Core.Settings;
using XDM.Platform;

namespace XDM.Core.Tests;

public sealed class ConfiguredHttpClientFactoryTests
{
    [Fact]
    public async Task DegradedSettingsClientFailsClosedInsteadOfUsingDirectNetwork()
    {
        using HttpClient client = ConfiguredHttpClientFactory.Create(new DegradedSettingsService());

        InvalidOperationException error = await Assert.ThrowsAsync<InvalidOperationException>(() =>
            client.GetAsync("https://example.test/file"));

        Assert.Contains("Network access is disabled", error.Message, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public async Task InvalidAutomaticProxyConfigurationProducesFailClosedClient()
    {
        ApplicationSettings settings = ApplicationSettings.CreateDefault() with
        {
            Network = NetworkSettings.Default with
            {
                Proxy = ProxySettings.SystemDefault with
                {
                    Mode = ProxyMode.AutomaticScript,
                    AutomaticConfigurationUrl = null
                }
            }
        };

        using HttpClient client = ConfiguredHttpClientFactory.Create(settings);
        InvalidOperationException error = await Assert.ThrowsAsync<InvalidOperationException>(() =>
            client.GetAsync("https://example.test/file"));

        Assert.Contains("proxy policy", error.Message, StringComparison.OrdinalIgnoreCase);
    }

    private sealed class DegradedSettingsService : ISettingsService
    {
        public ApplicationSettings Current { get; } = ApplicationSettings.CreateDefault();
        public bool IsOperational => false;
        public string? LoadFailureMessage => "proxy settings unreadable";
        public event EventHandler<ApplicationSettings>? Changed
        {
            add { }
            remove { }
        }
        public Task InitializeAsync(CancellationToken cancellationToken = default) => Task.CompletedTask;
        public Task UpdateAsync(ApplicationSettings settings, CancellationToken cancellationToken = default) => Task.CompletedTask;
    }
}
