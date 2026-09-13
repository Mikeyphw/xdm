using XDM.Core.Settings;
using XDM.Persistence;

namespace XDM.DownloadEngine.Tests;

public sealed class SettingsServiceTests
{
    [Fact]
    public async Task InitializeFailureLeavesExplicitDegradedState()
    {
        SettingsService service = new(new FailingStore());

        await Assert.ThrowsAsync<InvalidDataException>(() => service.InitializeAsync());

        Assert.False(service.IsOperational);
        Assert.Contains("required proxy", service.LoadFailureMessage, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public async Task SuccessfulSaveClearsDegradedState()
    {
        RecoverableStore store = new();
        SettingsService service = new(store);
        await Assert.ThrowsAsync<InvalidDataException>(() => service.InitializeAsync());

        await service.UpdateAsync(ApplicationSettings.CreateDefault());

        Assert.True(service.IsOperational);
        Assert.Null(service.LoadFailureMessage);
    }

    private sealed class FailingStore : ISettingsStore
    {
        public Task<ApplicationSettings?> LoadAsync(CancellationToken cancellationToken = default)
            => Task.FromException<ApplicationSettings?>(new InvalidDataException("required proxy settings could not be loaded"));

        public Task SaveAsync(ApplicationSettings settings, CancellationToken cancellationToken = default)
            => Task.CompletedTask;
    }

    private sealed class RecoverableStore : ISettingsStore
    {
        public Task<ApplicationSettings?> LoadAsync(CancellationToken cancellationToken = default)
            => Task.FromException<ApplicationSettings?>(new InvalidDataException("required proxy settings could not be loaded"));

        public Task SaveAsync(ApplicationSettings settings, CancellationToken cancellationToken = default)
            => Task.CompletedTask;
    }
}
