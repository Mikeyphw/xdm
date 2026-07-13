using Microsoft.Extensions.Logging;
using XDM.Core.Policies;
using XDM.Core.Settings;

namespace XDM.DownloadEngine.Policies;

public sealed class TransferPolicyRuntime : ITransferPolicyRuntime
{
    private static readonly TimeSpan RefreshInterval = TimeSpan.FromSeconds(15);
    private static readonly Action<ILogger, Exception?> EnvironmentRefreshFailed =
        LoggerMessage.Define(LogLevel.Warning, new EventId(5201, nameof(EnvironmentRefreshFailed)), "Smart transfer environment refresh failed.");
    private readonly ISettingsService _settingsService;
    private readonly ITransferEnvironmentProbe _environmentProbe;
    private readonly ILogger<TransferPolicyRuntime> _logger;
    private readonly SemaphoreSlim _refreshGate = new(1, 1);
    private readonly object _sync = new();
    private readonly HashSet<string> _scheduleProfileIds = new(StringComparer.Ordinal);
    private CancellationTokenSource? _lifetimeCancellation;
    private Task? _loopTask;
    private TransferPolicySnapshot _current = TransferPolicySnapshot.Unrestricted;
    private bool _disposed;

    public TransferPolicyRuntime(
        ISettingsService settingsService,
        ITransferEnvironmentProbe environmentProbe,
        ILogger<TransferPolicyRuntime> logger)
    {
        _settingsService = settingsService;
        _environmentProbe = environmentProbe;
        _logger = logger;
    }

    public TransferPolicySnapshot Current
    {
        get
        {
            lock (_sync)
            {
                return _current;
            }
        }
    }

    public event EventHandler<TransferPolicySnapshot>? Changed;

    public async Task InitializeAsync(CancellationToken cancellationToken = default)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        if (_loopTask is { IsCompleted: false })
        {
            return;
        }

        _settingsService.Changed += OnSettingsChanged;
        _lifetimeCancellation = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        await RefreshAsync(cancellationToken).ConfigureAwait(false);
        _loopTask = RunLoopAsync(_lifetimeCancellation.Token);
    }

    public async Task RefreshAsync(CancellationToken cancellationToken = default)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        await _refreshGate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            TransferEnvironmentSnapshot detected = await _environmentProbe
                .GetSnapshotAsync(cancellationToken)
                .ConfigureAwait(false);
            ApplicationSettings settings = _settingsService.Current.Normalize();
            SmartTransferSettings smart = (settings.SmartTransfers ?? SmartTransferSettings.Default).Normalize();
            string[] scheduleProfiles;
            lock (_sync)
            {
                scheduleProfiles = _scheduleProfileIds.OrderBy(static id => id, StringComparer.Ordinal).ToArray();
            }

            TransferPolicySnapshot next = BuildSnapshot(settings, smart, detected, scheduleProfiles);
            Publish(next);
        }
        finally
        {
            _refreshGate.Release();
        }
    }

    public void SetScheduleProfileOverrides(IEnumerable<string> profileIds)
    {
        ArgumentNullException.ThrowIfNull(profileIds);
        bool changed;
        lock (_sync)
        {
            HashSet<string> normalized = profileIds
                .Where(static id => !string.IsNullOrWhiteSpace(id))
                .Select(static id => id.Trim())
                .ToHashSet(StringComparer.Ordinal);
            changed = !_scheduleProfileIds.SetEquals(normalized);
            if (changed)
            {
                _scheduleProfileIds.Clear();
                _scheduleProfileIds.UnionWith(normalized);
            }
        }

        if (changed)
        {
            _ = RefreshSafelyAsync();
        }
    }

    public void Dispose()
    {
        DisposeAsync().AsTask().GetAwaiter().GetResult();
        GC.SuppressFinalize(this);
    }

    public async ValueTask DisposeAsync()
    {
        if (_disposed)
        {
            return;
        }

        _disposed = true;
        _settingsService.Changed -= OnSettingsChanged;
        if (_lifetimeCancellation is not null)
        {
            await _lifetimeCancellation.CancelAsync().ConfigureAwait(false);
        }

        if (_loopTask is not null)
        {
            try
            {
                await _loopTask.ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
            }
        }

        _lifetimeCancellation?.Dispose();
        _refreshGate.Dispose();
        GC.SuppressFinalize(this);
    }

    private async Task RunLoopAsync(CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested)
        {
            await Task.Delay(RefreshInterval, cancellationToken).ConfigureAwait(false);
            await RefreshAsync(cancellationToken).ConfigureAwait(false);
        }
    }

    private static TransferPolicySnapshot BuildSnapshot(
        ApplicationSettings settings,
        SmartTransferSettings smart,
        TransferEnvironmentSnapshot detected,
        string[] scheduleProfileIds)
    {
        bool? isMetered = smart.NetworkCostOverride switch
        {
            NetworkCostOverride.Metered => true,
            NetworkCostOverride.Unmetered => false,
            _ => detected.IsMetered
        };
        bool? isOnBattery = smart.PowerSourceOverride switch
        {
            PowerSourceOverride.Battery => true,
            PowerSourceOverride.AcPower => false,
            _ => detected.IsOnBattery
        };
        TransferEnvironmentSnapshot environment = detected with
        {
            IsMetered = isMetered,
            IsOnBattery = isOnBattery
        };

        if (!smart.Enabled)
        {
            BandwidthProfile unrestricted = new(
                "unrestricted",
                "Unrestricted",
                settings.MaxConcurrentDownloads,
                settings.MaxConcurrentDownloads,
                settings.DefaultSpeedLimitBytesPerSecond);
            return new TransferPolicySnapshot(
                unrestricted,
                environment,
                false,
                "Smart transfer policy is disabled.",
                [],
                DateTimeOffset.UtcNow);
        }

        Dictionary<string, BandwidthProfile> profiles = smart.Profiles
            .ToDictionary(static profile => profile.Id, StringComparer.Ordinal);
        List<BandwidthProfile> selected = [];
        selected.Add(profiles[smart.ActiveProfileId]);
        foreach (string scheduleProfileId in scheduleProfileIds.Distinct(StringComparer.Ordinal))
        {
            if (profiles.TryGetValue(scheduleProfileId, out BandwidthProfile? profile))
            {
                selected.Add(profile);
            }
        }

        bool paused = false;
        List<string> reasons = [];
        if (!environment.IsNetworkAvailable && smart.PauseWhenOffline)
        {
            paused = true;
            reasons.Add("network is offline");
        }

        ApplyEnvironmentBehavior(
            environment.IsMetered == true,
            smart.MeteredBehavior,
            smart.MeteredProfileId,
            "metered network",
            profiles,
            selected,
            reasons,
            ref paused);
        ApplyEnvironmentBehavior(
            environment.IsOnBattery == true,
            smart.BatteryBehavior,
            smart.BatteryProfileId,
            "battery power",
            profiles,
            selected,
            reasons,
            ref paused);

        BandwidthProfile effective = MergeProfiles(selected, settings.MaxConcurrentDownloads);
        string environmentSummary = $"Network: {FormatNetworkState(environment)} • Power: {FormatPowerState(environment)}";
        string status = paused
            ? $"Transfers paused because {string.Join(" and ", reasons)}. {environmentSummary}."
            : $"Profile: {effective.Name} • {effective.MaxConcurrentDownloads} concurrent • {effective.MaxConcurrentPerHost}/host • {FormatSpeed(effective.SpeedLimitBytesPerSecond)}. {environmentSummary}.";
        return new TransferPolicySnapshot(
            effective,
            environment,
            paused,
            status,
            scheduleProfileIds.ToArray(),
            DateTimeOffset.UtcNow);
    }

    private static void ApplyEnvironmentBehavior(
        bool condition,
        TransferPolicyBehavior behavior,
        string profileId,
        string reason,
        Dictionary<string, BandwidthProfile> profiles,
        List<BandwidthProfile> selected,
        List<string> reasons,
        ref bool paused)
    {
        if (!condition)
        {
            return;
        }

        if (behavior == TransferPolicyBehavior.Pause)
        {
            paused = true;
            reasons.Add(reason);
        }
        else if (behavior == TransferPolicyBehavior.UseProfile
            && profiles.TryGetValue(profileId, out BandwidthProfile? profile))
        {
            selected.Add(profile);
        }
    }

    private static BandwidthProfile MergeProfiles(
        List<BandwidthProfile> profiles,
        int applicationMaximum)
    {
        int concurrent = Math.Min(
            Math.Max(1, applicationMaximum),
            profiles.Min(static profile => profile.MaxConcurrentDownloads));
        int perHost = profiles.Min(static profile => profile.MaxConcurrentPerHost);
        long speed = profiles
            .Select(static profile => profile.SpeedLimitBytesPerSecond)
            .Where(static value => value > 0)
            .DefaultIfEmpty(0)
            .Min();
        string[] names = profiles.Select(static profile => profile.Name).Distinct(StringComparer.Ordinal).ToArray();
        return new BandwidthProfile(
            "effective",
            string.Join(" + ", names),
            concurrent,
            perHost,
            speed);
    }

    private static string FormatSpeed(long bytesPerSecond)
        => bytesPerSecond <= 0
            ? "unlimited"
            : FormattableString.Invariant($"{bytesPerSecond / 1024d:0.#} KiB/s");

    private static string FormatNetworkState(TransferEnvironmentSnapshot environment)
        => !environment.IsNetworkAvailable
            ? "offline"
            : environment.IsMetered switch
            {
                true => "metered",
                false => "unmetered",
                null => "available (cost unknown)"
            };

    private static string FormatPowerState(TransferEnvironmentSnapshot environment)
        => environment.IsOnBattery switch
        {
            true => "battery",
            false => "AC power",
            null => "unknown"
        };

    private void OnSettingsChanged(object? sender, ApplicationSettings settings)
        => _ = RefreshSafelyAsync();

    private async Task RefreshSafelyAsync()
    {
        try
        {
            await RefreshAsync().ConfigureAwait(false);
        }
        catch (ObjectDisposedException)
        {
        }
        catch (OperationCanceledException)
        {
        }
        catch (IOException exception)
        {
            EnvironmentRefreshFailed(_logger, exception);
        }
        catch (UnauthorizedAccessException exception)
        {
            EnvironmentRefreshFailed(_logger, exception);
        }
    }

    private void Publish(TransferPolicySnapshot snapshot)
    {
        bool changed;
        lock (_sync)
        {
            changed = HasMeaningfulChange(_current, snapshot);
            _current = snapshot;
        }

        if (changed)
        {
            Changed?.Invoke(this, snapshot);
        }
    }

    private static bool HasMeaningfulChange(
        TransferPolicySnapshot previous,
        TransferPolicySnapshot current)
        => previous.EffectiveProfile != current.EffectiveProfile
            || previous.Environment.IsNetworkAvailable != current.Environment.IsNetworkAvailable
            || previous.Environment.IsMetered != current.Environment.IsMetered
            || previous.Environment.IsOnBattery != current.Environment.IsOnBattery
            || !string.Equals(previous.Environment.Source, current.Environment.Source, StringComparison.Ordinal)
            || previous.IsPaused != current.IsPaused
            || !string.Equals(previous.StatusMessage, current.StatusMessage, StringComparison.Ordinal)
            || !previous.ActiveScheduleProfileIds.SequenceEqual(
                current.ActiveScheduleProfileIds,
                StringComparer.Ordinal);
}

internal sealed class UnrestrictedTransferPolicyRuntime : ITransferPolicyRuntime
{
    public static UnrestrictedTransferPolicyRuntime Instance { get; } = new();

    public TransferPolicySnapshot Current => TransferPolicySnapshot.Unrestricted;

    public event EventHandler<TransferPolicySnapshot>? Changed
    {
        add { }
        remove { }
    }

    public Task InitializeAsync(CancellationToken cancellationToken = default)
        => Task.CompletedTask;

    public Task RefreshAsync(CancellationToken cancellationToken = default)
        => Task.CompletedTask;

    public void SetScheduleProfileOverrides(IEnumerable<string> profileIds)
    {
    }

    public void Dispose()
    {
    }

    public ValueTask DisposeAsync()
        => ValueTask.CompletedTask;
}
