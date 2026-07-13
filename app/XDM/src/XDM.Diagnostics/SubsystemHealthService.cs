using System.Diagnostics;
using XDM.BrowserIntegration;
using XDM.Core.Settings;
using XDM.DownloadEngine.Aria2;
using XDM.Media;

namespace XDM.Diagnostics;

public sealed class SubsystemHealthService : ISubsystemHealthService, IDisposable
{
    public const string RepairBrowserNativeHost = "browser-native-host";
    public const string RepairAria2 = "aria2";

    private const int DiskProbeBytes = 64 * 1024;
    private static readonly TimeSpan CheckTimeout = TimeSpan.FromSeconds(10);
    private readonly IBrowserIntegrationService _browserIntegration;
    private readonly IBrowserHostInstaller _browserHostInstaller;
    private readonly IAria2Service _aria2Service;
    private readonly IFfmpegService _ffmpegService;
    private readonly ISettingsService _settingsService;
    private readonly IDiagnosticEventStore _events;
    private readonly SemaphoreSlim _gate = new(1, 1);
    private SubsystemHealthSnapshot _current = SubsystemHealthSnapshot.Empty;

    public SubsystemHealthService(
        IBrowserIntegrationService browserIntegration,
        IBrowserHostInstaller browserHostInstaller,
        IAria2Service aria2Service,
        IFfmpegService ffmpegService,
        ISettingsService settingsService,
        IDiagnosticEventStore events)
    {
        _browserIntegration = browserIntegration;
        _browserHostInstaller = browserHostInstaller;
        _aria2Service = aria2Service;
        _ffmpegService = ffmpegService;
        _settingsService = settingsService;
        _events = events;
    }

    public SubsystemHealthSnapshot Current => _current;

    public event EventHandler<SubsystemHealthSnapshot>? Changed;

    public async Task<SubsystemHealthSnapshot> RefreshAsync(
        string destinationDirectory,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(destinationDirectory);
        await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            List<SubsystemHealthCheckResult> checks =
            [
                CheckBrowserBridge(),
                CheckNativeHost(),
                await CheckAria2Async(cancellationToken).ConfigureAwait(false),
                await CheckFfmpegAsync(cancellationToken).ConfigureAwait(false),
                CheckProxy(),
                await CheckDestinationAsync(destinationDirectory, cancellationToken).ConfigureAwait(false)
            ];
            Publish(new SubsystemHealthSnapshot(DateTimeOffset.UtcNow, checks));
            _events.Record(
                DiagnosticSeverity.Information,
                "XDM-DIAGNOSTICS-HEALTH",
                _current.ProblemCount == 0
                    ? "Subsystem health checks completed without actionable failures."
                    : $"Subsystem health checks found {_current.ProblemCount} actionable issue(s).");
            return _current;
        }
        finally
        {
            _gate.Release();
        }
    }

    public async Task<SubsystemHealthSnapshot> RepairAsync(
        string repairActionId,
        string destinationDirectory,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(repairActionId);
        switch (repairActionId)
        {
            case RepairBrowserNativeHost:
                BrowserHostInstallationStatus repaired = await _browserHostInstaller
                    .RepairAsync(_browserIntegration.Current.ExtensionId, cancellationToken)
                    .ConfigureAwait(false);
                _events.Record(
                    repaired.IsCompatible ? DiagnosticSeverity.Information : DiagnosticSeverity.Warning,
                    "XDM-DIAGNOSTICS-REPAIR-BROWSER",
                    SecretRedactor.Redact(repaired.Message));
                break;
            case RepairAria2:
                Aria2IntegrationSettings aria2 = (_settingsService.Current.Aria2
                    ?? Aria2IntegrationSettings.Default).Normalize();
                if (!aria2.Enabled)
                {
                    throw new InvalidOperationException("Enable aria2 integration before attempting repair.");
                }

                if (aria2.ConnectionMode == Aria2ConnectionMode.ManagedProcess)
                {
                    await _aria2Service.StartManagedProcessAsync(cancellationToken).ConfigureAwait(false);
                }
                else
                {
                    await _aria2Service.RefreshAsync(cancellationToken).ConfigureAwait(false);
                }

                _events.Record(
                    DiagnosticSeverity.Information,
                    "XDM-DIAGNOSTICS-REPAIR-ARIA2",
                    "aria2 repair action completed; health was refreshed.");
                break;
            default:
                throw new ArgumentOutOfRangeException(
                    nameof(repairActionId),
                    repairActionId,
                    "Unknown diagnostics repair action.");
        }

        return await RefreshAsync(destinationDirectory, cancellationToken).ConfigureAwait(false);
    }

    private SubsystemHealthCheckResult CheckBrowserBridge()
    {
        Stopwatch stopwatch = Stopwatch.StartNew();
        BrowserIntegrationStatus status = _browserIntegration.Current;
        SubsystemHealthStatus health;
        string summary;
        if (!status.IsListening)
        {
            health = SubsystemHealthStatus.Unavailable;
            summary = "The browser bridge is not listening.";
        }
        else if (!string.IsNullOrWhiteSpace(status.LastError))
        {
            health = SubsystemHealthStatus.Degraded;
            summary = "The browser bridge is listening but reported a recent error.";
        }
        else if (status.LastExtensionHealthAt is null)
        {
            health = SubsystemHealthStatus.Degraded;
            summary = "The bridge is ready, but no extension health handshake has been received.";
        }
        else if (!string.Equals(status.ExtensionCompatibility, "compatible", StringComparison.Ordinal))
        {
            health = SubsystemHealthStatus.Degraded;
            summary = "The connected browser extension is not protocol-compatible.";
        }
        else
        {
            health = SubsystemHealthStatus.Healthy;
            summary = "Browser bridge and extension protocol handshake are healthy.";
        }

        stopwatch.Stop();
        string details = string.Join(
            Environment.NewLine,
            $"Listening: {status.IsListening}",
            $"Host protocol: {status.ProtocolVersion}",
            $"Expected native protocol: {BrowserNativeProtocol.ProtocolVersion}",
            $"Extension: {status.ExtensionBrowser ?? "not connected"} {status.ExtensionVersion ?? string.Empty}".TrimEnd(),
            $"Compatibility: {status.ExtensionCompatibility ?? "not reported"}",
            $"Last extension health: {status.LastExtensionHealthAt?.ToString("O", System.Globalization.CultureInfo.InvariantCulture) ?? "never"}",
            string.IsNullOrWhiteSpace(status.LastError)
                ? "Last error: none"
                : $"Last error: {SecretRedactor.Redact(status.LastError)}");
        return new SubsystemHealthCheckResult(
            "browser-bridge",
            "Browser extension bridge",
            health,
            summary,
            details,
            stopwatch.Elapsed);
    }

    private SubsystemHealthCheckResult CheckNativeHost()
    {
        Stopwatch stopwatch = Stopwatch.StartNew();
        BrowserHostInstallationStatus status = _browserHostInstaller.GetStatus();
        stopwatch.Stop();
        bool installed = status.NativeHostExists
            && status.IsCompatible
            && status.CompatibleManifestCount > 0;
        SubsystemHealthStatus health = installed
            ? SubsystemHealthStatus.Healthy
            : status.NativeHostExists
                ? SubsystemHealthStatus.Degraded
                : SubsystemHealthStatus.Unavailable;
        string details = string.Join(
            Environment.NewLine,
            $"Native host executable: {(status.NativeHostExists ? "found" : "missing")}",
            $"Firefox manifest: {(status.FirefoxManifestInstalled ? "installed" : "missing")}",
            $"Chromium manifests: {status.ChromiumManifestCount}",
            $"Compatible manifests: {status.CompatibleManifestCount}",
            SecretRedactor.Redact(status.Message));
        return new SubsystemHealthCheckResult(
            "native-host",
            "Native host registration",
            health,
            installed ? "Native messaging registration is compatible." : "Native messaging registration needs repair.",
            details,
            stopwatch.Elapsed,
            installed ? null : RepairBrowserNativeHost,
            installed ? null : "Repair registration");
    }

    private async Task<SubsystemHealthCheckResult> CheckAria2Async(CancellationToken cancellationToken)
    {
        Aria2IntegrationSettings settings = (_settingsService.Current.Aria2
            ?? Aria2IntegrationSettings.Default).Normalize();
        if (!settings.Enabled)
        {
            return new SubsystemHealthCheckResult(
                "aria2",
                "aria2 backend",
                SubsystemHealthStatus.Disabled,
                "aria2 integration is disabled.",
                $"Mode: {settings.ConnectionMode}{Environment.NewLine}RPC endpoint: {GetOrigin(settings.RpcEndpoint)}",
                TimeSpan.Zero);
        }

        Stopwatch stopwatch = Stopwatch.StartNew();
        try
        {
            using CancellationTokenSource timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            timeout.CancelAfter(CheckTimeout);
            await _aria2Service.RefreshAsync(timeout.Token).ConfigureAwait(false);
            Aria2ServiceSnapshot snapshot = _aria2Service.Current;
            stopwatch.Stop();
            SubsystemHealthStatus status = snapshot.Health.IsAvailable
                ? SubsystemHealthStatus.Healthy
                : SubsystemHealthStatus.Unavailable;
            string details = string.Join(
                Environment.NewLine,
                $"Mode: {settings.ConnectionMode}",
                $"RPC endpoint: {GetOrigin(settings.RpcEndpoint)}",
                $"RPC latency: {stopwatch.Elapsed.TotalMilliseconds:0} ms",
                $"Managed process: {(snapshot.Health.IsManagedProcessRunning ? "running" : "not running")}",
                $"Version: {snapshot.Health.Version ?? "unknown"}",
                $"Tracked tasks: {snapshot.Tasks.Count}",
                SecretRedactor.Redact(snapshot.Health.Message));
            return new SubsystemHealthCheckResult(
                "aria2",
                "aria2 backend",
                status,
                snapshot.Health.IsAvailable
                    ? $"aria2 RPC responded in {stopwatch.Elapsed.TotalMilliseconds:0} ms."
                    : "aria2 is enabled but unavailable.",
                details,
                stopwatch.Elapsed,
                snapshot.Health.IsAvailable ? null : RepairAria2,
                snapshot.Health.IsAvailable ? null : settings.ConnectionMode == Aria2ConnectionMode.ManagedProcess
                    ? "Start and reconnect"
                    : "Retry RPC");
        }
        catch (Exception exception) when (exception is not OperationCanceledException || !cancellationToken.IsCancellationRequested)
        {
            stopwatch.Stop();
            return new SubsystemHealthCheckResult(
                "aria2",
                "aria2 backend",
                SubsystemHealthStatus.Unavailable,
                "aria2 health check failed.",
                SecretRedactor.Redact(exception.Message),
                stopwatch.Elapsed,
                RepairAria2,
                settings.ConnectionMode == Aria2ConnectionMode.ManagedProcess ? "Start and reconnect" : "Retry RPC");
        }
    }

    private async Task<SubsystemHealthCheckResult> CheckFfmpegAsync(CancellationToken cancellationToken)
    {
        Stopwatch stopwatch = Stopwatch.StartNew();
        try
        {
            using CancellationTokenSource timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            timeout.CancelAfter(CheckTimeout);
            FfmpegCapabilities capabilities = await _ffmpegService
                .GetCapabilitiesAsync(timeout.Token)
                .ConfigureAwait(false);
            stopwatch.Stop();
            return new SubsystemHealthCheckResult(
                "ffmpeg",
                "FFmpeg",
                capabilities.Health.IsAvailable
                    ? SubsystemHealthStatus.Healthy
                    : SubsystemHealthStatus.Unavailable,
                capabilities.Summary,
                string.Join(
                    Environment.NewLine,
                    $"Executable: {capabilities.Health.ExecutablePath ?? "not found"}",
                    $"Version: {capabilities.Health.Version ?? "unknown"}",
                    SecretRedactor.Redact(capabilities.Health.Message)),
                stopwatch.Elapsed);
        }
        catch (Exception exception) when (exception is not OperationCanceledException || !cancellationToken.IsCancellationRequested)
        {
            stopwatch.Stop();
            return new SubsystemHealthCheckResult(
                "ffmpeg",
                "FFmpeg",
                SubsystemHealthStatus.Unavailable,
                "FFmpeg capability detection failed.",
                SecretRedactor.Redact(exception.Message),
                stopwatch.Elapsed);
        }
    }

    private SubsystemHealthCheckResult CheckProxy()
    {
        Stopwatch stopwatch = Stopwatch.StartNew();
        ProxySettings raw = _settingsService.Current.Network?.Proxy ?? ProxySettings.SystemDefault;
        ProxySettings proxy = raw.Normalize();
        SubsystemHealthStatus status = raw.Mode == proxy.Mode
            ? SubsystemHealthStatus.Healthy
            : SubsystemHealthStatus.Degraded;
        string summary = proxy.Mode switch
        {
            ProxyMode.None => "Direct connections are configured.",
            ProxyMode.System => "System proxy discovery is configured.",
            ProxyMode.Manual => "Manual proxy configuration is structurally valid.",
            ProxyMode.AutomaticScript => "Automatic proxy script configuration is structurally valid.",
            _ => "Proxy configuration was normalized."
        };
        if (status == SubsystemHealthStatus.Degraded)
        {
            summary = $"Invalid {raw.Mode} proxy settings were normalized to {proxy.Mode}.";
        }

        stopwatch.Stop();
        string endpoint = proxy.Mode == ProxyMode.Manual
            ? $"{proxy.Host}:{proxy.Port}"
            : proxy.Mode == ProxyMode.AutomaticScript
                ? GetOrigin(proxy.AutomaticConfigurationUrl)
                : "not applicable";
        return new SubsystemHealthCheckResult(
            "proxy",
            "Proxy configuration",
            status,
            summary,
            string.Join(
                Environment.NewLine,
                $"Mode: {proxy.Mode}",
                $"Endpoint: {endpoint}",
                $"Authentication: {proxy.AuthenticationMode}",
                $"Bypass local: {proxy.BypassLocal}",
                $"Bypass rules: {proxy.BypassList?.Count ?? 0}"),
            stopwatch.Elapsed);
    }

    private static async Task<SubsystemHealthCheckResult> CheckDestinationAsync(
        string destinationDirectory,
        CancellationToken cancellationToken)
    {
        Stopwatch stopwatch = Stopwatch.StartNew();
        string fullPath = Path.GetFullPath(destinationDirectory);
        string? probePath = null;
        try
        {
            Directory.CreateDirectory(fullPath);
            DriveInfo drive = new(Path.GetPathRoot(fullPath)
                ?? throw new IOException("Destination does not have a filesystem root."));
            probePath = Path.Combine(fullPath, $".xdm-health-{Guid.NewGuid():N}.tmp");
            byte[] buffer = new byte[DiskProbeBytes];
            await using (FileStream stream = new(
                probePath,
                FileMode.CreateNew,
                FileAccess.Write,
                FileShare.None,
                16 * 1024,
                FileOptions.Asynchronous | FileOptions.WriteThrough))
            {
                await stream.WriteAsync(buffer, cancellationToken).ConfigureAwait(false);
                await stream.FlushAsync(cancellationToken).ConfigureAwait(false);
            }

            stopwatch.Stop();
            double mibPerSecond = DiskProbeBytes / 1024d / 1024d
                / Math.Max(stopwatch.Elapsed.TotalSeconds, 0.000_001);
            return new SubsystemHealthCheckResult(
                "destination-disk",
                "Destination disk",
                SubsystemHealthStatus.Healthy,
                "Destination is writable and has a readable free-space result.",
                string.Join(
                    Environment.NewLine,
                    $"Directory: {fullPath}",
                    $"Available bytes: {drive.AvailableFreeSpace}",
                    $"Bounded write: {DiskProbeBytes} bytes",
                    $"Observed write rate: {mibPerSecond:0.0} MiB/s"),
                stopwatch.Elapsed);
        }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException or ArgumentException)
        {
            stopwatch.Stop();
            return new SubsystemHealthCheckResult(
                "destination-disk",
                "Destination disk",
                SubsystemHealthStatus.Unavailable,
                "The destination write-access check failed.",
                SecretRedactor.Redact(exception.Message),
                stopwatch.Elapsed);
        }
        finally
        {
            if (probePath is not null)
            {
                try
                {
                    File.Delete(probePath);
                }
                catch (IOException)
                {
                }
                catch (UnauthorizedAccessException)
                {
                }
            }
        }
    }

    public void Dispose()
    {
        _gate.Dispose();
        GC.SuppressFinalize(this);
    }

    private void Publish(SubsystemHealthSnapshot snapshot)
    {
        _current = snapshot;
        Changed?.Invoke(this, snapshot);
    }

    private static string GetOrigin(string? value)
        => Uri.TryCreate(value, UriKind.Absolute, out Uri? uri)
            ? uri.GetLeftPart(UriPartial.Authority)
            : "invalid or unavailable";
}
