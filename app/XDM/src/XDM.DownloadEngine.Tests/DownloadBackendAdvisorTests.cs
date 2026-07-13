using XDM.Core.Downloads;
using XDM.Core.Settings;
using XDM.DownloadEngine.Aria2;
using XDM.DownloadEngine.Backends;

namespace XDM.DownloadEngine.Tests;

public sealed class DownloadBackendAdvisorTests
{
    private static readonly Aria2ServiceSnapshot HealthyAria2 = new(
        new Aria2Health(true, true, "aria2 is ready.", "1.37.0"),
        [],
        DateTimeOffset.UtcNow,
        false);

    [Fact]
    public void ExplicitNativeSelectionAlwaysUsesNativeBackend()
    {
        DownloadRequest request = CreateRequest(DownloadBackendPreference.Native);

        DownloadBackendDecision decision = DownloadBackendAdvisor.Decide(
            request,
            EnabledSettings(),
            HealthyAria2);

        Assert.Equal(DownloadBackendKind.Native, decision.Backend);
        Assert.True(decision.CanStart);
    }

    [Fact]
    public void LargeAutomaticTransferUsesHealthyAria2()
    {
        DownloadRequest request = CreateRequest(
            DownloadBackendPreference.Automatic,
            expectedLength: 512L * 1024 * 1024);

        DownloadBackendDecision decision = DownloadBackendAdvisor.Decide(
            request,
            EnabledSettings(),
            HealthyAria2);

        Assert.Equal(DownloadBackendKind.Aria2, decision.Backend);
        Assert.Contains("large", decision.Reason, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public void MirroredAutomaticTransferUsesHealthyAria2()
    {
        DownloadRequest request = CreateRequest(DownloadBackendPreference.Automatic) with
        {
            Mirrors = [new Uri("https://mirror.example.test/file.bin")]
        };

        DownloadBackendDecision decision = DownloadBackendAdvisor.Decide(
            request,
            EnabledSettings(),
            HealthyAria2);

        Assert.Equal(DownloadBackendKind.Aria2, decision.Backend);
        Assert.Contains("mirrors", decision.Reason, StringComparison.OrdinalIgnoreCase);
    }


    [Fact]
    public void FtpsTransferStaysOnNativeBackend()
    {
        DownloadRequest request = CreateRequest(DownloadBackendPreference.Automatic) with
        {
            Source = new Uri("ftps://example.test/file.bin"),
            ExpectedLength = 512L * 1024 * 1024
        };

        DownloadBackendDecision decision = DownloadBackendAdvisor.Decide(
            request,
            EnabledSettings(),
            HealthyAria2);

        Assert.Equal(DownloadBackendKind.Native, decision.Backend);
        Assert.True(decision.CanStart);
    }

    [Fact]
    public void ExplicitAria2FallsBackWhenUnavailableAndAllowed()
    {
        DownloadRequest request = CreateRequest(DownloadBackendPreference.Aria2) with
        {
            AllowBackendFallback = true
        };

        DownloadBackendDecision decision = DownloadBackendAdvisor.Decide(
            request,
            EnabledSettings(),
            Aria2ServiceSnapshot.Disabled);

        Assert.Equal(DownloadBackendKind.Native, decision.Backend);
        Assert.True(decision.IsFallback);
        Assert.True(decision.CanStart);
    }

    [Fact]
    public void ExplicitAria2BlocksUnsupportedPostWithoutFallback()
    {
        DownloadRequest request = CreateRequest(DownloadBackendPreference.Aria2) with
        {
            Method = "POST",
            RequestBody = [1, 2, 3],
            AllowBackendFallback = false
        };

        DownloadBackendDecision decision = DownloadBackendAdvisor.Decide(
            request,
            EnabledSettings(),
            HealthyAria2);

        Assert.Equal(DownloadBackendKind.Aria2, decision.Backend);
        Assert.False(decision.CanStart);
    }

    private static DownloadRequest CreateRequest(
        DownloadBackendPreference preference,
        long? expectedLength = null)
        => new(
            new Uri("https://example.test/file.bin"),
            Path.GetTempPath(),
            ExpectedLength: expectedLength,
            BackendPreference: preference);

    private static Aria2IntegrationSettings EnabledSettings()
        => Aria2IntegrationSettings.Default with
        {
            Enabled = true,
            AutomaticRoutingEnabled = true,
            PreferForMirrors = true,
            AutomaticRoutingMinimumBytes = 128L * 1024 * 1024,
            AutomaticRoutingMinimumConnections = 8
        };
}
