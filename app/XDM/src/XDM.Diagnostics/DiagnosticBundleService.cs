using System.IO.Compression;
using System.Text.Json;
using XDM.BrowserIntegration;
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

    public DiagnosticBundleService(
        IDiagnosticEventStore events,
        IApplicationState applicationState,
        ISettingsService settingsService,
        IBrowserIntegrationService browserIntegration,
        IPlatformInfo platformInfo,
        IRecoveryService recoveryService)
    {
        _events = events;
        _applicationState = applicationState;
        _settingsService = settingsService;
        _browserIntegration = browserIntegration;
        _platformInfo = platformInfo;
        _recoveryService = recoveryService;
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
            previousSessionWasUnclean = _recoveryService.PreviousSessionWasUnclean
        }, cancellationToken).ConfigureAwait(false);
        await AddJsonAsync(archive, "events.json", _events.Snapshot(), cancellationToken).ConfigureAwait(false);
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
