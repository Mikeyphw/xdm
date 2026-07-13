using System.IO.Compression;
using System.Text.Json;
using XDM.BrowserIntegration;
using XDM.Core.Diagnostics;
using XDM.Core.Settings;
using XDM.Core.State;
using XDM.Platform;

namespace XDM.Diagnostics;

public sealed class DiagnosticBundleService : IDiagnosticBundleService
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        WriteIndented = true
    };

    private readonly IDiagnosticEventStore _events;
    private readonly IApplicationState _applicationState;
    private readonly ISettingsService _settingsService;
    private readonly IBrowserIntegrationService _browserIntegration;
    private readonly IPlatformInfo _platformInfo;
    private readonly IRecoveryService _recoveryService;
    private readonly ITransferDiagnosticSource _transferDiagnostics;
    private readonly ITransferHealthProbe _healthProbe;
    private readonly ISubsystemHealthService _subsystemHealth;
    private readonly IDeterministicDownloadTestService _downloadTest;

    public DiagnosticBundleService(
        IDiagnosticEventStore events,
        IApplicationState applicationState,
        ISettingsService settingsService,
        IBrowserIntegrationService browserIntegration,
        IPlatformInfo platformInfo,
        IRecoveryService recoveryService,
        ITransferDiagnosticSource transferDiagnostics,
        ITransferHealthProbe healthProbe,
        ISubsystemHealthService subsystemHealth,
        IDeterministicDownloadTestService downloadTest)
    {
        _events = events;
        _applicationState = applicationState;
        _settingsService = settingsService;
        _browserIntegration = browserIntegration;
        _platformInfo = platformInfo;
        _recoveryService = recoveryService;
        _transferDiagnostics = transferDiagnostics;
        _healthProbe = healthProbe;
        _subsystemHealth = subsystemHealth;
        _downloadTest = downloadTest;
    }

    public async Task<string> ExportAsync(
        string destinationDirectory,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(destinationDirectory);
        Directory.CreateDirectory(destinationDirectory);
        string timestamp = DateTimeOffset.UtcNow.ToString(
            "yyyyMMdd-HHmmss",
            System.Globalization.CultureInfo.InvariantCulture);
        string archivePath = Path.Combine(
            destinationDirectory,
            $"xdm-diagnostics-{timestamp}.zip");

        await using FileStream output = new(
            archivePath,
            FileMode.CreateNew,
            FileAccess.ReadWrite,
            FileShare.None,
            64 * 1024,
            FileOptions.Asynchronous);
        using ZipArchive archive = new(output, ZipArchiveMode.Create, leaveOpen: true);

        BrowserIntegrationStatus browser = _browserIntegration.Current;
        await AddJsonAsync(archive, "summary.json", new
        {
            generatedAt = DateTimeOffset.UtcNow,
            application = "XDM Modern",
            runtime = _platformInfo.Runtime,
            operatingSystem = _platformInfo.OperatingSystem,
            architecture = _platformInfo.Architecture,
            safeMode = _recoveryService.SafeMode,
            currentSessionId = _recoveryService.SessionId,
            previousSessionWasUnclean = _recoveryService.PreviousSessionWasUnclean,
            previousSession = _recoveryService.PreviousSession
        }, cancellationToken).ConfigureAwait(false);
        await AddJsonAsync(archive, "events.json", _events.Snapshot(), cancellationToken).ConfigureAwait(false);
        IReadOnlyList<TransferDiagnosticEvent> transferTimeline = _transferDiagnostics.Snapshot();
        await AddJsonAsync(
            archive,
            "transfer-timeline.json",
            transferTimeline,
            cancellationToken).ConfigureAwait(false);
        await AddJsonAsync(
            archive,
            "transfer-insights.json",
            transferTimeline
                .GroupBy(static item => item.DownloadId, StringComparer.Ordinal)
                .ToDictionary(
                    static group => group.Key,
                    static group => TransferDiagnosticInsightBuilder.Build(group.ToArray()),
                    StringComparer.Ordinal),
            cancellationToken).ConfigureAwait(false);
        await AddJsonAsync(
            archive,
            "subsystem-health.json",
            _subsystemHealth.Current,
            cancellationToken).ConfigureAwait(false);
        if (_downloadTest.LastResult is DeterministicDownloadTestResult downloadTest)
        {
            await AddJsonAsync(
                archive,
                "deterministic-download-test.json",
                downloadTest,
                cancellationToken).ConfigureAwait(false);
        }
        if (_healthProbe.LastResult is TransferHealthProbeResult healthProbe)
        {
            await AddJsonAsync(archive, "live-health-probe.json", healthProbe, cancellationToken).ConfigureAwait(false);
        }
        await AddJsonAsync(archive, "downloads.json", _applicationState.Current.Downloads.Select(static download => new
        {
            download.Id,
            download.FileName,
            SourceOrigin = download.Source.GetLeftPart(UriPartial.Authority),
            DestinationFileName = Path.GetFileName(download.DestinationPath),
            download.DownloadedBytes,
            download.TotalBytes,
            download.State,
            ErrorMessage = download.ErrorMessage is null ? null : SecretRedactor.Redact(download.ErrorMessage),
            download.QueueId,
            download.CategoryId
        }).ToArray(), cancellationToken).ConfigureAwait(false);
        ApplicationSettings settings = _settingsService.Current;
        await AddJsonAsync(archive, "settings.json", new
        {
            settings.SchemaVersion,
            settings.MaxConcurrentDownloads,
            settings.DefaultSpeedLimitBytesPerSecond,
            settings.ClipboardMonitoringEnabled,
            settings.AutoAddClipboardLinks,
            Categories = settings.Categories.Select(static category => new
            {
                category.Id,
                category.Name,
                category.Extensions
            }).ToArray(),
            Queues = settings.Queues.Select(static queue => new
            {
                queue.Id,
                queue.Name,
                queue.MaxConcurrentDownloads,
                queue.SpeedLimitBytesPerSecond
            }).ToArray(),
            settings.Scheduler
        }, cancellationToken).ConfigureAwait(false);
        await AddJsonAsync(archive, "browser.json", new
        {
            browser.IsListening,
            browser.Port,
            browser.ProtocolVersion,
            browser.StartedAt,
            browser.LastMessageAt,
            browser.LastBrowser,
            LastCapturedOrigin = browser.LastCapturedUrl is null
                ? null
                : GetOrigin(browser.LastCapturedUrl),
            browser.LastExtensionHealthAt,
            browser.ExtensionBrowser,
            browser.ExtensionBrowserVersion,
            browser.ExtensionVersion,
            browser.ExtensionManifestVersion,
            browser.ExtensionIncognitoAllowed,
            browser.ExtensionEnhancedAccessGranted,
            browser.ExtensionGrantedOrigins,
            browser.ExtensionCompatibility,
            browser.ExtensionCapabilities,
            LastError = browser.LastError is null ? null : SecretRedactor.Redact(browser.LastError)
        }, cancellationToken).ConfigureAwait(false);

        return archivePath;
    }

    private static string GetOrigin(string url)
        => Uri.TryCreate(url, UriKind.Absolute, out Uri? uri)
            ? uri.GetLeftPart(UriPartial.Authority)
            : "[invalid URL]";

    private static async Task AddJsonAsync<T>(
        ZipArchive archive,
        string entryName,
        T value,
        CancellationToken cancellationToken)
    {
        ZipArchiveEntry entry = archive.CreateEntry(entryName, CompressionLevel.SmallestSize);
        await using Stream stream = entry.Open();
        await JsonSerializer.SerializeAsync(stream, value, JsonOptions, cancellationToken)
            .ConfigureAwait(false);
    }
}
