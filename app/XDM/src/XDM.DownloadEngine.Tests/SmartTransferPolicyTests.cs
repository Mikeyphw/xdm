using Microsoft.Extensions.Logging.Abstractions;
using XDM.Core.Policies;
using XDM.Core.Settings;
using XDM.DownloadEngine.Policies;

namespace XDM.DownloadEngine.Tests;

public sealed class SmartTransferPolicyTests
{
    [Fact]
    public async Task MeteredNetworkUsesConfiguredProfile()
    {
        ApplicationSettings settings = ApplicationSettings.CreateDefault() with
        {
            SmartTransfers = SmartTransferSettings.Default with
            {
                ActiveProfileId = "balanced",
                MeteredBehavior = TransferPolicyBehavior.UseProfile,
                MeteredProfileId = "metered"
            }
        };
        MutableSettingsService settingsService = new(settings);
        FixedEnvironmentProbe environment = new(new TransferEnvironmentSnapshot(
            true,
            true,
            false,
            "Test",
            DateTimeOffset.UtcNow));
        await using TransferPolicyRuntime runtime = new(
            settingsService,
            environment,
            NullLogger<TransferPolicyRuntime>.Instance);

        await runtime.RefreshAsync();

        Assert.False(runtime.Current.IsPaused);
        Assert.Equal(1, runtime.Current.EffectiveProfile.MaxConcurrentDownloads);
        Assert.Equal(256L * 1024, runtime.Current.EffectiveProfile.SpeedLimitBytesPerSecond);
    }

    [Fact]
    public async Task MeteredPauseOverridesProfiles()
    {
        ApplicationSettings settings = ApplicationSettings.CreateDefault() with
        {
            SmartTransfers = SmartTransferSettings.Default with
            {
                MeteredBehavior = TransferPolicyBehavior.Pause
            }
        };
        MutableSettingsService settingsService = new(settings);
        FixedEnvironmentProbe environment = new(new TransferEnvironmentSnapshot(
            true,
            true,
            false,
            "Test",
            DateTimeOffset.UtcNow));
        await using TransferPolicyRuntime runtime = new(
            settingsService,
            environment,
            NullLogger<TransferPolicyRuntime>.Instance);

        await runtime.RefreshAsync();

        Assert.True(runtime.Current.IsPaused);
        Assert.Contains("metered network", runtime.Current.StatusMessage, StringComparison.Ordinal);
    }

    [Fact]
    public async Task ScheduleOverrideCombinesUsingMostRestrictiveLimits()
    {
        ApplicationSettings settings = ApplicationSettings.CreateDefault();
        MutableSettingsService settingsService = new(settings);
        FixedEnvironmentProbe environment = new(TransferEnvironmentSnapshot.Unknown);
        await using TransferPolicyRuntime runtime = new(
            settingsService,
            environment,
            NullLogger<TransferPolicyRuntime>.Instance);

        runtime.SetScheduleProfileOverrides(["gaming"]);
        await runtime.RefreshAsync();

        Assert.Equal(1, runtime.Current.EffectiveProfile.MaxConcurrentDownloads);
        Assert.Equal(1, runtime.Current.EffectiveProfile.MaxConcurrentPerHost);
        Assert.Equal(512L * 1024, runtime.Current.EffectiveProfile.SpeedLimitBytesPerSecond);
        Assert.Contains("gaming", runtime.Current.ActiveScheduleProfileIds);
    }

    [Fact]
    public async Task RefreshWithoutPolicyChangesDoesNotRaiseAnotherEvent()
    {
        MutableSettingsService settingsService = new(ApplicationSettings.CreateDefault());
        FixedEnvironmentProbe environment = new(TransferEnvironmentSnapshot.Unknown);
        await using TransferPolicyRuntime runtime = new(
            settingsService,
            environment,
            NullLogger<TransferPolicyRuntime>.Instance);
        await runtime.RefreshAsync();
        int notifications = 0;
        runtime.Changed += (_, _) => notifications++;

        await runtime.RefreshAsync();

        Assert.Equal(0, notifications);
    }

    [Fact]
    public void ProfileNormalizationKeepsKnownSelectionsAndBoundsLimits()
    {
        SmartTransferSettings normalized = (SmartTransferSettings.Default with
        {
            Profiles = [new BandwidthProfile("custom", "Custom", 99, 0, -1)],
            ActiveProfileId = "missing",
            MeteredProfileId = "missing",
            BatteryProfileId = "missing"
        }).Normalize();

        BandwidthProfile profile = Assert.Single(normalized.Profiles);
        Assert.Equal("custom", normalized.ActiveProfileId);
        Assert.Equal(32, profile.MaxConcurrentDownloads);
        Assert.Equal(1, profile.MaxConcurrentPerHost);
        Assert.Equal(0, profile.SpeedLimitBytesPerSecond);
    }

    private sealed class FixedEnvironmentProbe(TransferEnvironmentSnapshot snapshot) : ITransferEnvironmentProbe
    {
        public Task<TransferEnvironmentSnapshot> GetSnapshotAsync(CancellationToken cancellationToken = default)
            => Task.FromResult(snapshot with { UpdatedAt = DateTimeOffset.UtcNow });
    }

    private sealed class MutableSettingsService(ApplicationSettings current) : ISettingsService
    {
        public ApplicationSettings Current { get; private set; } = current.Normalize();

        public event EventHandler<ApplicationSettings>? Changed;

        public Task InitializeAsync(CancellationToken cancellationToken = default)
            => Task.CompletedTask;

        public Task UpdateAsync(ApplicationSettings settings, CancellationToken cancellationToken = default)
        {
            Current = settings.Normalize();
            Changed?.Invoke(this, Current);
            return Task.CompletedTask;
        }
    }
}
