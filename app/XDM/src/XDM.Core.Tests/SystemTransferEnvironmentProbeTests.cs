using XDM.Platform;

namespace XDM.Core.Tests;

public sealed class SystemTransferEnvironmentProbeTests
{
    [Fact]
    public async Task AutoDetectionKeepsUnknownMeteredStateWhenPlatformCannotResolveCost()
    {
        using EnvironmentVariableScope meteredOverride = new("XDM_NETWORK_METERED", null);
        using EnvironmentVariableScope batteryOverride = new("XDM_ON_BATTERY", null);
        SystemTransferEnvironmentProbe probe = new(new FixedTransferEnvironmentDetector(
            networkAvailable: true,
            metered: null,
            onBattery: null));

        var snapshot = await probe.GetSnapshotAsync();

        Assert.True(snapshot.IsNetworkAvailable);
        Assert.Null(snapshot.IsMetered);
        Assert.Null(snapshot.IsOnBattery);
        Assert.Contains("network cost unknown", snapshot.Source, StringComparison.Ordinal);
        Assert.Contains("power source unknown", snapshot.Source, StringComparison.Ordinal);
    }

    [Fact]
    public async Task EnvironmentOverridesRemainExplicitAndDoNotMasqueradeAsAutoDetection()
    {
        using EnvironmentVariableScope meteredOverride = new("XDM_NETWORK_METERED", "true");
        using EnvironmentVariableScope batteryOverride = new("XDM_ON_BATTERY", "false");
        SystemTransferEnvironmentProbe probe = new(new FixedTransferEnvironmentDetector(
            networkAvailable: true,
            metered: false,
            onBattery: true));

        var snapshot = await probe.GetSnapshotAsync();

        Assert.True(snapshot.IsMetered);
        Assert.False(snapshot.IsOnBattery);
        Assert.Contains("metered override", snapshot.Source, StringComparison.Ordinal);
        Assert.Contains("battery override", snapshot.Source, StringComparison.Ordinal);
    }

    [Fact]
    public async Task CancellationIsPropagatedBeforeDetectorWork()
    {
        using CancellationTokenSource cancellation = new();
        await cancellation.CancelAsync();
        SystemTransferEnvironmentProbe probe = new(new FixedTransferEnvironmentDetector(
            networkAvailable: true,
            metered: false,
            onBattery: false));

        await Assert.ThrowsAsync<OperationCanceledException>(() => probe.GetSnapshotAsync(cancellation.Token));
    }

    private sealed class FixedTransferEnvironmentDetector(
        bool networkAvailable,
        bool? metered,
        bool? onBattery) : ITransferEnvironmentDetector
    {
        public bool IsNetworkAvailable() => networkAvailable;

        public bool? DetectMeteredNetwork(CancellationToken cancellationToken = default)
        {
            cancellationToken.ThrowIfCancellationRequested();
            return metered;
        }

        public bool? DetectOnBattery(CancellationToken cancellationToken = default)
        {
            cancellationToken.ThrowIfCancellationRequested();
            return onBattery;
        }
    }

    private sealed class EnvironmentVariableScope : IDisposable
    {
        private readonly string _name;
        private readonly string? _original;

        public EnvironmentVariableScope(string name, string? value)
        {
            _name = name;
            _original = Environment.GetEnvironmentVariable(name);
            Environment.SetEnvironmentVariable(name, value);
        }

        public void Dispose()
            => Environment.SetEnvironmentVariable(_name, _original);
    }
}
