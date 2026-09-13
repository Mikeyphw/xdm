using System.IO.Compression;
using XDM.BrowserIntegration;
using XDM.Core.Diagnostics;
using XDM.Core.Downloads;
using XDM.Core.Settings;
using XDM.Core.State;
using XDM.Platform;

namespace XDM.Diagnostics.Tests;

public sealed class DiagnosticBundleServiceTests
{
    [Fact]
    public async Task ExportAsyncPublishesAtomicallyAndRedactsBundleContents()
    {
        string directory = Path.Combine(Path.GetTempPath(), $"xdm-bundle-{Guid.NewGuid():N}");
        Directory.CreateDirectory(directory);
        try
        {
            DiagnosticEventStore events = new();
            events.Record(
                DiagnosticSeverity.Error,
                "XDM-TEST",
                "Authorization: Basic dXNlcjpwYXNz /home/tester/private/error.log");
            TransferDiagnosticStore transfer = new();
            transfer.Record(
                "download-a",
                TransferDiagnosticStage.Http,
                TransferDiagnosticSeverity.Warning,
                "XDM-TRANSFER-TEST",
                "https://user:pass@example.test/file?api_key=secret");
            DiagnosticBundleService service = new(
                events,
                new FakeApplicationState(),
                new FakeSettingsService(),
                new FakeBrowserIntegration(),
                new FakePlatformInfo(),
                new FakeRecoveryService(),
                transfer,
                new FakeTransferHealthProbe(),
                new FakeSubsystemHealthService(),
                new FakeDownloadTestService());

            string archivePath = await service.ExportAsync(directory);

            Assert.True(File.Exists(archivePath));
            Assert.Empty(Directory.GetFiles(directory, "*.xdm-finalizing"));
            using ZipArchive archive = ZipFile.OpenRead(archivePath);
            string combined = string.Join("\n", archive.Entries.Select(ReadEntry));

            Assert.DoesNotContain("dXNlcjpwYXNz", combined, StringComparison.Ordinal);
            Assert.DoesNotContain("user:pass", combined, StringComparison.Ordinal);
            Assert.DoesNotContain("api_key=secret", combined, StringComparison.Ordinal);
            Assert.DoesNotContain("access_token=secret", combined, StringComparison.Ordinal);
            Assert.DoesNotContain("/home/tester/private", combined, StringComparison.Ordinal);
            Assert.Contains("https://example.test", combined, StringComparison.Ordinal);
        }
        finally
        {
            Directory.Delete(directory, recursive: true);
        }
    }

    private static string ReadEntry(ZipArchiveEntry entry)
    {
        using Stream stream = entry.Open();
        using StreamReader reader = new(stream);
        return reader.ReadToEnd();
    }

    private sealed class FakeApplicationState : IApplicationState
    {
        public ApplicationSnapshot Current { get; } = new(
            DateTimeOffset.UtcNow,
            true,
            [
                new DownloadSnapshot(
                    "download-a",
                    "video.mp4",
                    new Uri("https://user:pass@example.test/file?access_token=secret"),
                    "/home/tester/private/video.mp4",
                    10,
                    100,
                    0,
                    DownloadState.Downloading,
                    DateTimeOffset.UtcNow,
                    ErrorMessage: "Cookie: session=secret")
            ]);

        public event EventHandler<ApplicationSnapshot>? Changed
        {
            add { }
            remove { }
        }

        public void SetCoreReady(bool ready) { }

        public void ReplaceDownloads(IEnumerable<DownloadSnapshot> downloads) { }

        public void UpsertDownload(DownloadSnapshot download) { }

        public bool RemoveDownload(string downloadId) => false;
    }

    private sealed class FakeSettingsService : ISettingsService
    {
        public ApplicationSettings Current { get; private set; } = ApplicationSettings.CreateDefault();

        public event EventHandler<ApplicationSettings>? Changed
        {
            add { }
            remove { }
        }

        public Task InitializeAsync(CancellationToken cancellationToken = default) => Task.CompletedTask;

        public Task UpdateAsync(ApplicationSettings settings, CancellationToken cancellationToken = default)
        {
            Current = settings;
            return Task.CompletedTask;
        }
    }

    private sealed class FakeBrowserIntegration : IBrowserIntegrationService
    {
        public BrowserIntegrationStatus Current { get; } = new(
            true,
            9614,
            BrowserNativeProtocol.ProtocolVersion,
            "auth-token-secret",
            LastCapturedUrl: "https://user:pass@example.test/capture?token=secret",
            LastExtensionHealthAt: DateTimeOffset.UtcNow,
            ExtensionBrowser: "Test Browser",
            ExtensionVersion: BrowserNativeProtocol.MinimumExtensionVersion,
            ExtensionCompatibility: "compatible",
            ExtensionGrantedOrigins: ["https://user:pass@example.test/private?token=secret"]);

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

        public Task InitializeAsync(CancellationToken cancellationToken = default) => Task.CompletedTask;

        public Task StopAsync(CancellationToken cancellationToken = default) => Task.CompletedTask;

        public void Dispose() { }
    }

    private sealed class FakePlatformInfo : IPlatformInfo
    {
        public string OperatingSystem => "Test OS /home/tester";

        public string Architecture => "x64";

        public string Runtime => ".NET test";

        public string DisplayName => "Test platform";
    }

    private sealed class FakeRecoveryService : IRecoveryService
    {
        public bool SafeMode => false;

        public bool PreviousSessionWasUnclean => true;

        public string StateDirectory => "/home/tester/.xdm/state";

        public string SessionId => "session";

        public ApplicationSessionState? PreviousSession => null;

        public void Initialize(StartupOptions options) { }

        public void BeginShutdown(IReadOnlyList<string> activeDownloadIds) { }

        public void RecordCheckpointFlush(bool succeeded, int attempted, int written, IReadOnlyList<string> failedDownloadIds) { }

        public void MarkCleanShutdown() { }
    }

    private sealed class FakeTransferHealthProbe : ITransferHealthProbe
    {
        public TransferHealthProbeResult? LastResult => null;

        public event EventHandler? Changed
        {
            add { }
            remove { }
        }

        public Task<TransferHealthProbeResult> ProbeAsync(Uri target, string destinationDirectory, CancellationToken cancellationToken = default)
            => throw new NotSupportedException();
    }

    private sealed class FakeSubsystemHealthService : ISubsystemHealthService
    {
        public SubsystemHealthSnapshot Current => SubsystemHealthSnapshot.Empty;

        public event EventHandler<SubsystemHealthSnapshot>? Changed
        {
            add { }
            remove { }
        }

        public Task<SubsystemHealthSnapshot> RefreshAsync(string destinationDirectory, CancellationToken cancellationToken = default)
            => Task.FromResult(Current);

        public Task<SubsystemHealthSnapshot> RepairAsync(string repairActionId, string destinationDirectory, CancellationToken cancellationToken = default)
            => Task.FromResult(Current);
    }

    private sealed class FakeDownloadTestService : IDeterministicDownloadTestService
    {
        public DeterministicDownloadTestResult? LastResult => null;

        public event EventHandler? Changed
        {
            add { }
            remove { }
        }

        public Task<DeterministicDownloadTestResult> RunAsync(CancellationToken cancellationToken = default)
            => throw new NotSupportedException();
    }
}
