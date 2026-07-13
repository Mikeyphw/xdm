using XDM.BrowserIntegration;
using XDM.Core.Settings;
using XDM.DownloadEngine.Aria2;
using XDM.Media;

namespace XDM.Diagnostics.Tests;

public sealed class SubsystemHealthServiceTests
{
    [Fact]
    public async Task RefreshAsyncReportsEachBoundedSubsystemCheck()
    {
        string directory = Path.Combine(Path.GetTempPath(), $"xdm-health-{Guid.NewGuid():N}");
        Directory.CreateDirectory(directory);
        try
        {
            ApplicationSettings settings = ApplicationSettings.CreateDefault() with
            {
                DefaultDownloadDirectory = directory,
                Aria2 = Aria2IntegrationSettings.Default with { Enabled = false }
            };
            using SubsystemHealthService service = new(
                new FakeBrowserIntegration(),
                new FakeBrowserHostInstaller(),
                new FakeAria2Service(),
                new FakeFfmpegService(),
                new FakeSettingsService(settings),
                new DiagnosticEventStore());

            SubsystemHealthSnapshot result = await service.RefreshAsync(directory);

            Assert.Equal(6, result.Checks.Count);
            Assert.Contains(result.Checks, static check => check.Id == "browser-bridge" && check.Status == SubsystemHealthStatus.Healthy);
            Assert.Contains(result.Checks, static check => check.Id == "native-host" && check.Status == SubsystemHealthStatus.Healthy);
            Assert.Contains(result.Checks, static check => check.Id == "aria2" && check.Status == SubsystemHealthStatus.Disabled);
            Assert.Contains(result.Checks, static check => check.Id == "ffmpeg" && check.Status == SubsystemHealthStatus.Healthy);
            Assert.Contains(result.Checks, static check => check.Id == "proxy" && check.Status == SubsystemHealthStatus.Healthy);
            Assert.Contains(result.Checks, static check => check.Id == "destination-disk" && check.Status == SubsystemHealthStatus.Healthy);
            Assert.Equal(0, result.ProblemCount);
        }
        finally
        {
            Directory.Delete(directory, recursive: true);
        }
    }

    private sealed class FakeBrowserIntegration : IBrowserIntegrationService
    {
        public event EventHandler<BrowserCaptureEventArgs>? CaptureReceived
        {
            add { }
            remove { }
        }

        public event EventHandler<BrowserStatusChangedEventArgs>? StatusChanged
        {
            add { }
            remove { }
        }

        public BrowserIntegrationStatus Current { get; } = new(
            true,
            9614,
            BrowserNativeProtocol.ProtocolVersion,
            "redacted",
            LastExtensionHealthAt: DateTimeOffset.UtcNow,
            ExtensionBrowser: "Test Browser",
            ExtensionVersion: BrowserNativeProtocol.MinimumExtensionVersion,
            ExtensionCompatibility: "compatible");

        public Task InitializeAsync(CancellationToken cancellationToken = default) => Task.CompletedTask;

        public Task StopAsync(CancellationToken cancellationToken = default) => Task.CompletedTask;

        public void Dispose()
        {
        }
    }

    private sealed class FakeBrowserHostInstaller : IBrowserHostInstaller
    {
        private static readonly BrowserHostInstallationStatus Healthy = new(
            true,
            true,
            1,
            "Native host is installed.",
            true,
            2,
            []);

        public BrowserHostInstallationStatus GetStatus() => Healthy;

        public Task<BrowserHostInstallationStatus> RepairAsync(
            string? chromiumExtensionId,
            CancellationToken cancellationToken = default)
            => Task.FromResult(Healthy);

        public Task<BrowserHostInstallationStatus> UninstallAsync(
            CancellationToken cancellationToken = default)
            => Task.FromResult(Healthy);
    }

    private sealed class FakeAria2Service : IAria2Service
    {
        public Aria2ServiceSnapshot Current { get; private set; } = Aria2ServiceSnapshot.Disabled;

        public event EventHandler<Aria2ServiceSnapshot>? Changed;

        public Task InitializeAsync(CancellationToken cancellationToken = default) => Task.CompletedTask;

        public Task ConfigureAsync(Aria2IntegrationSettings settings, CancellationToken cancellationToken = default)
            => Task.CompletedTask;

        public Task StartManagedProcessAsync(CancellationToken cancellationToken = default) => Task.CompletedTask;

        public Task StopManagedProcessAsync(CancellationToken cancellationToken = default) => Task.CompletedTask;

        public Task RefreshAsync(CancellationToken cancellationToken = default)
        {
            Changed?.Invoke(this, Current);
            return Task.CompletedTask;
        }

        public Task<string> AddAsync(Aria2AddRequest request, CancellationToken cancellationToken = default)
            => Task.FromResult("gid");

        public Task PauseAsync(string gid, CancellationToken cancellationToken = default) => Task.CompletedTask;

        public Task ResumeAsync(string gid, CancellationToken cancellationToken = default) => Task.CompletedTask;

        public Task RemoveAsync(string gid, CancellationToken cancellationToken = default) => Task.CompletedTask;
    }

    private sealed class FakeFfmpegService : IFfmpegService
    {
        public Task<ExternalToolHealth> GetHealthAsync(CancellationToken cancellationToken = default)
            => Task.FromResult(new ExternalToolHealth("ffmpeg", true, "/usr/bin/ffmpeg", "test", "Available"));

        public Task<FfmpegCapabilities> GetCapabilitiesAsync(CancellationToken cancellationToken = default)
            => Task.FromResult(new FfmpegCapabilities(
                new ExternalToolHealth("ffmpeg", true, "/usr/bin/ffmpeg", "test", "Available"),
                true,
                true,
                true,
                true,
                true,
                true));

        public Task MuxAsync(
            IReadOnlyList<string> inputPaths,
            string destinationPath,
            CancellationToken cancellationToken = default)
            => Task.CompletedTask;
    }

    private sealed class FakeSettingsService(ApplicationSettings current) : ISettingsService
    {
        public ApplicationSettings Current { get; private set; } = current.Normalize();

        public event EventHandler<ApplicationSettings>? Changed;

        public Task InitializeAsync(CancellationToken cancellationToken = default) => Task.CompletedTask;

        public Task UpdateAsync(ApplicationSettings settings, CancellationToken cancellationToken = default)
        {
            Current = settings.Normalize();
            Changed?.Invoke(this, Current);
            return Task.CompletedTask;
        }
    }
}
