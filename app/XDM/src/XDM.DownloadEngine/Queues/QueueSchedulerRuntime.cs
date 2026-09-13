using System.Security.Cryptography;
using System.Text;
using Microsoft.Extensions.Logging;
using XDM.Core.Abstractions;
using XDM.Core.Downloads;
using XDM.Core.Policies;
using XDM.Core.Scheduling;
using XDM.Core.Settings;
using XDM.Core.State;

namespace XDM.DownloadEngine.Queues;

public interface IQueueSchedulerRuntime : IDisposable, IAsyncDisposable
{
    bool IsRunning { get; }

    DateTimeOffset? NextEvaluationAt { get; }

    SchedulerRuntimeSnapshot Current { get; }

    event EventHandler<SchedulerRuntimeSnapshot>? Changed;

    Task InitializeAsync(CancellationToken cancellationToken = default);

    Task EvaluateAsync(CancellationToken cancellationToken = default);

    bool CancelPendingAction();
}

public sealed class QueueSchedulerRuntime : IQueueSchedulerRuntime
{
    private static readonly TimeSpan EvaluationInterval = TimeSpan.FromSeconds(15);
    private static readonly Action<ILogger, string, Exception?> QueueStarted =
        LoggerMessage.Define<string>(LogLevel.Information, new EventId(5101, nameof(QueueStarted)), "Scheduled queue {QueueId} started.");
    private static readonly Action<ILogger, string, Exception?> QueueStopped =
        LoggerMessage.Define<string>(LogLevel.Information, new EventId(5102, nameof(QueueStopped)), "Scheduled queue {QueueId} stopped.");
    private static readonly Action<ILogger, string, string, Exception?> ActionFinished =
        LoggerMessage.Define<string, string>(LogLevel.Information, new EventId(5103, nameof(ActionFinished)), "Schedule {ScheduleId}: {Message}");

    private readonly IDownloadManager _downloadManager;
    private readonly ISettingsService _settingsService;
    private readonly IApplicationState _applicationState;
    private readonly ISchedulerStateStore _stateStore;
    private readonly ICompletionActionService _completionActions;
    private readonly IAntivirusScanner _antivirusScanner;
    private readonly ILogger<QueueSchedulerRuntime> _logger;
    private readonly ITransferPolicyRuntime _transferPolicyRuntime;
    private readonly SemaphoreSlim _evaluationGate = new(1, 1);
    private readonly Dictionary<string, ActiveScheduleRun> _runs = new(StringComparer.Ordinal);
    private readonly Dictionary<string, int> _managedQueueRefCounts = new(StringComparer.Ordinal);
    private readonly Dictionary<string, CancellationTokenSource> _completionCancellations = new(StringComparer.Ordinal);
    private readonly object _snapshotSync = new();
    private readonly object _evaluationRequestSync = new();
    private SchedulerRuntimeState _state = SchedulerRuntimeState.Empty;
    private SchedulerRuntimeSnapshot _current = SchedulerRuntimeSnapshot.Empty;
    private CancellationTokenSource? _lifetimeCancellation;
    private Task? _loopTask;
    private Task? _evaluationPumpTask;
    private Task? _debouncedEvaluationTask;
    private bool _evaluationRequested;
    private bool _disposed;

    public QueueSchedulerRuntime(
        IDownloadManager downloadManager,
        ISettingsService settingsService,
        ILogger<QueueSchedulerRuntime> logger)
        : this(
            downloadManager,
            settingsService,
            new ApplicationState(),
            new InMemorySchedulerStateStore(),
            new NoOpCompletionActionService(),
            new NoOpAntivirusScanner(),
            logger)
    {
    }

    public QueueSchedulerRuntime(
        IDownloadManager downloadManager,
        ISettingsService settingsService,
        IApplicationState applicationState,
        ISchedulerStateStore stateStore,
        ICompletionActionService completionActions,
        IAntivirusScanner antivirusScanner,
        ILogger<QueueSchedulerRuntime> logger,
        ITransferPolicyRuntime? transferPolicyRuntime = null)
    {
        _downloadManager = downloadManager;
        _settingsService = settingsService;
        _applicationState = applicationState;
        _stateStore = stateStore;
        _completionActions = completionActions;
        _antivirusScanner = antivirusScanner;
        _logger = logger;
        _transferPolicyRuntime = transferPolicyRuntime ?? XDM.DownloadEngine.Policies.UnrestrictedTransferPolicyRuntime.Instance;
    }

    public bool IsRunning => _loopTask is { IsCompleted: false };

    public DateTimeOffset? NextEvaluationAt { get; private set; }

    public SchedulerRuntimeSnapshot Current
    {
        get
        {
            lock (_snapshotSync)
            {
                return _current;
            }
        }
    }

    public event EventHandler<SchedulerRuntimeSnapshot>? Changed;

    public async Task InitializeAsync(CancellationToken cancellationToken = default)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        if (_loopTask is { IsCompleted: false })
        {
            return;
        }

        _state = await _stateStore.LoadAsync(cancellationToken).ConfigureAwait(false);
        _lifetimeCancellation = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        _settingsService.Changed += OnSettingsChanged;
        _applicationState.Changed += OnApplicationStateChanged;
        await EvaluateAsync(cancellationToken).ConfigureAwait(false);
        _loopTask = RunLoopAsync(_lifetimeCancellation.Token);
    }

    public async Task EvaluateAsync(CancellationToken cancellationToken = default)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        await _evaluationGate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            DateTimeOffset now = DateTimeOffset.UtcNow;
            ApplicationSettings settings = _settingsService.Current.Normalize();
            ScheduleDefinitionRuntime[] definitions = BuildRuntimeDefinitions(settings).ToArray();
            HashSet<string> liveScheduleKeys = definitions.Select(static item => item.ScheduleKey).ToHashSet(StringComparer.Ordinal);
            Dictionary<string, ScheduleDefinitionRuntime> byKey = definitions.ToDictionary(static item => item.ScheduleKey, StringComparer.Ordinal);
            _state = _state.Normalize(liveScheduleKeys);
            RestorePersistedRuns(byKey);
            foreach (string completedRunId in _runs
                .Where(static pair => pair.Value.CompletionStarted)
                .Select(static pair => pair.Key)
                .ToArray())
            {
                _runs.Remove(completedRunId);
            }

            Dictionary<string, DateTimeOffset> lastStarts = _state.LastStartedWindows
                .Where(pair => liveScheduleKeys.Contains(pair.Key))
                .ToDictionary(static pair => pair.Key, static pair => pair.Value, StringComparer.Ordinal);
            ApplicationSnapshot snapshot = _applicationState.Current;
            HashSet<string> inWindowScheduleKeys = new(StringComparer.Ordinal);

            foreach (ScheduleDefinitionRuntime definition in definitions)
            {
                DateTimeOffset? currentWindow = ScheduleWindowCalculator.GetCurrentWindowStart(
                    definition.Schedule,
                    now,
                    TimeZoneInfo.Local);
                DateTimeOffset? missedWindow = null;
                if (currentWindow is null
                    && definition.Schedule.MissedRunPolicy == MissedRunPolicy.RunImmediately
                    && _state.LastEvaluationUtc != DateTimeOffset.MinValue)
                {
                    missedWindow = ScheduleWindowCalculator.GetLatestMissedStart(
                        definition.Schedule,
                        _state.LastEvaluationUtc,
                        now,
                        TimeZoneInfo.Local);
                }

                DateTimeOffset? candidateWindow = currentWindow ?? missedWindow;
                bool alreadyStarted = candidateWindow is not null
                    && (lastStarts.TryGetValue(definition.ScheduleKey, out DateTimeOffset previousStart)
                        || lastStarts.TryGetValue(definition.Schedule.Id, out previousStart))
                    && previousStart >= candidateWindow.Value;
                if (candidateWindow is not null && !alreadyStarted)
                {
                    ActiveScheduleRun run = await StartScheduleRunAsync(definition, candidateWindow.Value, snapshot, cancellationToken)
                        .ConfigureAwait(false);
                    _runs[run.RunId] = run;
                    lastStarts[definition.ScheduleKey] = candidateWindow.Value;
                }

                if (currentWindow is not null)
                {
                    inWindowScheduleKeys.Add(definition.ScheduleKey);
                    foreach (ActiveScheduleRun run in _runs.Values.Where(run =>
                        string.Equals(run.ScheduleKey, definition.ScheduleKey, StringComparison.Ordinal)))
                    {
                        TrackDownloadsForActiveWindow(run, snapshot, currentWindow.Value);
                    }
                }
            }

            EvaluateCompletedRuns(settings, inWindowScheduleKeys, snapshot, cancellationToken);

            HashSet<string> activeScheduleIds = _runs.Values
                .Where(static run => !run.CompletionStarted)
                .Select(static run => run.Schedule.Id)
                .ToHashSet(StringComparer.Ordinal);
            Dictionary<string, int> desiredQueues = BuildDesiredQueueRefCounts();

            string[] activeProfileIds = _runs.Values
                .Where(static run => !run.CompletionStarted)
                .Select(static run => run.Schedule.BandwidthProfileId)
                .Where(static profileId => !string.IsNullOrWhiteSpace(profileId))
                .Select(static profileId => profileId!)
                .Distinct(StringComparer.Ordinal)
                .ToArray();
            _transferPolicyRuntime.SetScheduleProfileOverrides(activeProfileIds);
            await SynchronizeManagedQueuesAsync(desiredQueues, cancellationToken).ConfigureAwait(false);

            _state = new SchedulerRuntimeState(
                now,
                lastStarts,
                _runs.Values
                    .Where(static run => !run.CompletionStarted)
                    .Select(static run => run.ToState())
                    .ToDictionary(static run => run.RunId, StringComparer.Ordinal));
            await _stateStore.SaveAsync(_state, cancellationToken).ConfigureAwait(false);
            NextEvaluationAt = now.Add(EvaluationInterval);
            Publish(new SchedulerRuntimeSnapshot(
                now,
                NextEvaluationAt,
                activeScheduleIds,
                BuildStatus(activeScheduleIds.Count, desiredQueues.Count),
                Current.PendingAction));
        }
        finally
        {
            _evaluationGate.Release();
        }
    }

    public bool CancelPendingAction()
    {
        CancellationTokenSource[] cancellations;
        lock (_completionCancellations)
        {
            cancellations = _completionCancellations.Values
                .Where(static cancellation => !cancellation.IsCancellationRequested)
                .ToArray();
        }

        foreach (CancellationTokenSource cancellation in cancellations)
        {
            cancellation.Cancel();
        }

        return cancellations.Length > 0;
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
        _transferPolicyRuntime.SetScheduleProfileOverrides([]);
        _settingsService.Changed -= OnSettingsChanged;
        _applicationState.Changed -= OnApplicationStateChanged;
        CancelPendingAction();
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

        if (_debouncedEvaluationTask is not null)
        {
            try
            {
                await _debouncedEvaluationTask.ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
            }
        }

        if (_evaluationPumpTask is not null)
        {
            try
            {
                await _evaluationPumpTask.ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
            }
        }

        CancellationTokenSource[] workflowCancellations;
        lock (_completionCancellations)
        {
            workflowCancellations = _completionCancellations.Values.ToArray();
            _completionCancellations.Clear();
        }

        foreach (CancellationTokenSource cancellation in workflowCancellations)
        {
            cancellation.Dispose();
        }

        _lifetimeCancellation?.Dispose();
        _evaluationGate.Dispose();
        GC.SuppressFinalize(this);
    }

    private async Task RunLoopAsync(CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested)
        {
            try
            {
                NextEvaluationAt = DateTimeOffset.UtcNow.Add(EvaluationInterval);
                await Task.Delay(EvaluationInterval, cancellationToken).ConfigureAwait(false);
                await EvaluateAsync(cancellationToken).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
            }
            catch (Exception exception) when (exception is IOException
                or UnauthorizedAccessException
                or InvalidOperationException
                or TimeoutException)
            {
                PublishStatus($"Scheduler evaluation failed and will retry: {exception.Message}");
            }
        }
    }

    private async Task<ActiveScheduleRun> StartScheduleRunAsync(
        ScheduleDefinitionRuntime definition,
        DateTimeOffset windowStartUtc,
        ApplicationSnapshot snapshot,
        CancellationToken cancellationToken)
    {
        string runId = $"{definition.ScheduleKey}:{windowStartUtc:yyyyMMddHHmmss}";
        ActiveScheduleRun run = new(
            runId,
            definition.Schedule,
            definition.ScheduleKey,
            windowStartUtc,
            DateTimeOffset.UtcNow,
            new HashSet<string>(StringComparer.Ordinal));
        TrackDownloadsForActiveWindow(run, snapshot, windowStartUtc);
        return run;
    }

    private async Task SynchronizeManagedQueuesAsync(
        Dictionary<string, int> desiredQueues,
        CancellationToken cancellationToken)
    {
        foreach ((string queueId, int desiredCount) in desiredQueues)
        {
            if (!_managedQueueRefCounts.ContainsKey(queueId))
            {
                await _downloadManager.StartQueueAsync(queueId, cancellationToken).ConfigureAwait(false);
                QueueStarted(_logger, queueId, null);
            }

            _managedQueueRefCounts[queueId] = desiredCount;
        }

        string[] noLongerDesired = _managedQueueRefCounts.Keys
            .Where(queueId => !desiredQueues.ContainsKey(queueId))
            .ToArray();
        foreach (string queueId in noLongerDesired)
        {
            await _downloadManager.StopQueueAsync(queueId, cancellationToken).ConfigureAwait(false);
            _managedQueueRefCounts.Remove(queueId);
            QueueStopped(_logger, queueId, null);
        }
    }

    private void EvaluateCompletedRuns(
        ApplicationSettings settings,
        HashSet<string> inWindowScheduleKeys,
        ApplicationSnapshot snapshot,
        CancellationToken cancellationToken)
    {
        Dictionary<string, DownloadSnapshot> downloads = snapshot.Downloads
            .ToDictionary(static download => download.Id, StringComparer.Ordinal);
        List<ActiveScheduleRun> completed = [];
        foreach (ActiveScheduleRun run in _runs.Values)
        {
            if (inWindowScheduleKeys.Contains(run.ScheduleKey) || run.CompletionStarted)
            {
                continue;
            }

            if (run.DownloadIds.Count == 0)
            {
                completed.Add(run);
                continue;
            }

            bool hasPending = run.DownloadIds.Any(id =>
                downloads.TryGetValue(id, out DownloadSnapshot? download)
                && !IsTerminal(download.State));
            if (!hasPending)
            {
                completed.Add(run);
            }
        }

        foreach (ActiveScheduleRun run in completed)
        {
            run.CompletionStarted = true;
            DownloadSnapshot[] completedDownloads = run.DownloadIds
                .Select(id => downloads.GetValueOrDefault(id))
                .Where(static download => download?.State == DownloadState.Completed)
                .Cast<DownloadSnapshot>()
                .ToArray();
            _ = ExecuteCompletionWorkflowSafelyAsync(
                run,
                completedDownloads,
                settings.Antivirus ?? AntivirusScanSettings.Disabled,
                cancellationToken);
        }
    }

    private async Task ExecuteCompletionWorkflowSafelyAsync(
        ActiveScheduleRun run,
        IReadOnlyList<DownloadSnapshot> completedDownloads,
        AntivirusScanSettings antivirus,
        CancellationToken schedulerCancellation)
    {
        try
        {
            await ExecuteCompletionWorkflowAsync(run, completedDownloads, antivirus, schedulerCancellation)
                .ConfigureAwait(false);
        }
        catch (Exception exception) when (exception is not OperationCanceledException)
        {
            PublishStatus($"Completion action for '{run.Schedule.Name}' failed: {exception.Message}");
        }
        finally
        {
            _ = RequestEvaluationAsync();
        }
    }

    private async Task ExecuteCompletionWorkflowAsync(
        ActiveScheduleRun run,
        IReadOnlyList<DownloadSnapshot> completedDownloads,
        AntivirusScanSettings antivirus,
        CancellationToken schedulerCancellation)
    {
        CancellationTokenSource cancellation = CancellationTokenSource.CreateLinkedTokenSource(schedulerCancellation);
        lock (_completionCancellations)
        {
            _completionCancellations[run.RunId] = cancellation;
        }

        try
        {
            if (antivirus.Enabled)
            {
                if (!_antivirusScanner.IsAvailable(antivirus))
                {
                    PublishStatus("Completion action cancelled because the configured antivirus is unavailable.");
                    return;
                }

                foreach (DownloadSnapshot download in completedDownloads)
                {
                    PublishPending(new PendingCompletionAction(
                        run.Schedule.Id,
                        run.Schedule.Name,
                        run.Schedule.CompletionAction.Kind,
                        DateTimeOffset.UtcNow,
                        0,
                        $"Scanning {download.FileName} before completion action."));
                    AntivirusScanResult scanResult = await _antivirusScanner
                        .ScanAsync(download.DestinationPath, antivirus, cancellation.Token)
                        .ConfigureAwait(false);
                    if (!scanResult.Succeeded)
                    {
                        PublishStatus($"Completion action cancelled: {scanResult.Message}");
                        return;
                    }
                }
            }

            ScheduleCompletionAction action = run.Schedule.CompletionAction.Normalize();
            if (action.Kind == ScheduleCompletionActionKind.None)
            {
                PublishStatus($"Schedule '{run.Schedule.Name}' completed.");
                return;
            }

            CompletionActionCapability? capability = _completionActions
                .GetCapabilities()
                .FirstOrDefault(item => item.Kind == action.Kind);
            if (capability is null || !capability.IsSupported)
            {
                PublishStatus(capability?.Message ?? $"Completion action {action.Kind} is unavailable.");
                return;
            }

            await CompletionActionCountdown.RunAsync(
                action.CountdownSeconds,
                remaining =>
                {
                    DateTimeOffset executeAt = DateTimeOffset.UtcNow.AddSeconds(remaining);
                    PublishPending(new PendingCompletionAction(
                        run.Schedule.Id,
                        run.Schedule.Name,
                        action.Kind,
                        executeAt,
                        remaining,
                        remaining == 0
                            ? $"Ready to execute {action.Kind}."
                            : $"{action.Kind} in {remaining} second{(remaining == 1 ? string.Empty : "s")}."));
                    return Task.CompletedTask;
                },
                cancellation.Token).ConfigureAwait(false);

            cancellation.Token.ThrowIfCancellationRequested();
            PublishPending(new PendingCompletionAction(
                run.Schedule.Id,
                run.Schedule.Name,
                action.Kind,
                DateTimeOffset.UtcNow,
                0,
                $"Executing {action.Kind}."));
            cancellation.Token.ThrowIfCancellationRequested();
            CompletionActionResult actionResult = await _completionActions
                .ExecuteAsync(action, cancellation.Token)
                .ConfigureAwait(false);
            ActionFinished(_logger, run.Schedule.Id, actionResult.Message, null);
            PublishStatus(actionResult.Message);
        }
        catch (OperationCanceledException) when (cancellation.IsCancellationRequested)
        {
            PublishStatus($"Completion action for '{run.Schedule.Name}' was cancelled.");
        }
        finally
        {
            lock (_completionCancellations)
            {
                _completionCancellations.Remove(run.RunId);
            }

            if (!HasPendingCancellations())
            {
                SchedulerRuntimeSnapshot current = Current;
                Publish(current with
                {
                    UpdatedAt = DateTimeOffset.UtcNow,
                    PendingAction = null
                });
            }

            cancellation.Dispose();
        }
    }

    private void OnSettingsChanged(object? sender, ApplicationSettings settings)
        => _ = RequestEvaluationAsync();

    private void OnApplicationStateChanged(object? sender, ApplicationSnapshot snapshot)
        => _ = RequestDebouncedEvaluationAsync();


    private Task RequestDebouncedEvaluationAsync()
    {
        lock (_evaluationRequestSync)
        {
            if (_debouncedEvaluationTask is { IsCompleted: false })
            {
                return _debouncedEvaluationTask;
            }

            _debouncedEvaluationTask = RunDebouncedEvaluationAsync();
            return _debouncedEvaluationTask;
        }
    }

    private async Task RunDebouncedEvaluationAsync()
    {
        CancellationToken cancellationToken = _lifetimeCancellation?.Token ?? CancellationToken.None;
        try
        {
            await Task.Delay(TimeSpan.FromMilliseconds(750), cancellationToken).ConfigureAwait(false);
            await RequestEvaluationAsync().ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
        }
    }

    private Task RequestEvaluationAsync()
    {
        lock (_evaluationRequestSync)
        {
            _evaluationRequested = true;
            if (_evaluationPumpTask is null || _evaluationPumpTask.IsCompleted)
            {
                _evaluationPumpTask = RunEvaluationPumpAsync();
            }

            return _evaluationPumpTask;
        }
    }

    private async Task RunEvaluationPumpAsync()
    {
        while (true)
        {
            CancellationToken cancellationToken = _lifetimeCancellation?.Token ?? CancellationToken.None;
            lock (_evaluationRequestSync)
            {
                if (!_evaluationRequested)
                {
                    return;
                }

                _evaluationRequested = false;
            }

            try
            {
                await EvaluateAsync(cancellationToken).ConfigureAwait(false);
            }
            catch (ObjectDisposedException)
            {
                return;
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                return;
            }
            catch (Exception exception) when (exception is IOException
                or UnauthorizedAccessException
                or InvalidOperationException
                or TimeoutException)
            {
                PublishStatus($"Scheduler evaluation failed and will retry: {exception.Message}");
            }
        }
    }

    private void RestorePersistedRuns(Dictionary<string, ScheduleDefinitionRuntime> definitions)
    {
        foreach (ScheduleRuntimeRunState runState in _state.ActiveRuns?.Values ?? [])
        {
            ScheduleRuntimeRunState normalized = runState.Normalize();
            if (_runs.ContainsKey(normalized.RunId)
                || !definitions.TryGetValue(normalized.ScheduleKey, out ScheduleDefinitionRuntime? definition))
            {
                continue;
            }

            _runs[normalized.RunId] = new ActiveScheduleRun(
                normalized.RunId,
                definition.Schedule,
                normalized.ScheduleKey,
                normalized.WindowStartUtc,
                normalized.DefinitionCapturedUtc,
                normalized.DownloadIds.ToHashSet(StringComparer.Ordinal))
            {
                CompletionStarted = normalized.CompletionStarted
            };
        }
    }

    private void TrackDownloadsForActiveWindow(
        ActiveScheduleRun run,
        ApplicationSnapshot snapshot,
        DateTimeOffset windowStartUtc)
    {
        foreach (DownloadSnapshot download in snapshot.Downloads.Where(download =>
            string.Equals(download.QueueId, run.Schedule.QueueId, StringComparison.Ordinal)
            && (!IsTerminal(download.State) || download.UpdatedAt >= windowStartUtc)))
        {
            run.DownloadIds.Add(download.Id);
        }
    }

    private Dictionary<string, int> BuildDesiredQueueRefCounts()
    {
        Dictionary<string, int> desired = new(StringComparer.Ordinal);
        foreach (ActiveScheduleRun run in _runs.Values.Where(static run => !run.CompletionStarted))
        {
            desired.TryGetValue(run.Schedule.QueueId, out int count);
            desired[run.Schedule.QueueId] = count + 1;
        }

        return desired;
    }

    private static IEnumerable<ScheduleDefinitionRuntime> BuildRuntimeDefinitions(ApplicationSettings settings)
    {
        foreach (QueueScheduleDefinition schedule in settings.Schedules?.Where(static schedule => schedule.Enabled) ?? [])
        {
            yield return new ScheduleDefinitionRuntime(schedule, CreateScheduleKey(schedule));
        }
    }

    private static string CreateScheduleKey(QueueScheduleDefinition schedule)
    {
        string payload = string.Join("|", [
            schedule.Id,
            schedule.QueueId,
            schedule.StartTime.ToString("HH:mm", System.Globalization.CultureInfo.InvariantCulture),
            schedule.EndTime.ToString("HH:mm", System.Globalization.CultureInfo.InvariantCulture),
            ((int)schedule.Days).ToString(System.Globalization.CultureInfo.InvariantCulture),
            schedule.MissedRunPolicy.ToString(),
            schedule.CompletionAction.Normalize().Kind.ToString(),
            schedule.CompletionAction.Normalize().CountdownSeconds.ToString(System.Globalization.CultureInfo.InvariantCulture),
            schedule.CompletionAction.Normalize().ExecutablePath ?? string.Empty,
            string.Join("\u001f", schedule.CompletionAction.Normalize().Arguments ?? []),
            schedule.BandwidthProfileId ?? string.Empty
        ]);
        byte[] hash = SHA256.HashData(Encoding.UTF8.GetBytes(payload));
        return $"{schedule.Id}:{Convert.ToHexString(hash)[..16]}";
    }

    private void PublishPending(PendingCompletionAction pending)
    {
        SchedulerRuntimeSnapshot current = Current;
        Publish(current with
        {
            UpdatedAt = DateTimeOffset.UtcNow,
            StatusMessage = pending.Message,
            PendingAction = pending
        });
    }

    private void PublishStatus(string message)
    {
        SchedulerRuntimeSnapshot current = Current;
        Publish(current with
        {
            UpdatedAt = DateTimeOffset.UtcNow,
            StatusMessage = message,
            PendingAction = HasPendingCancellations()
                ? current.PendingAction
                : null
        });
    }

    private bool HasPendingCancellations()
    {
        lock (_completionCancellations)
        {
            return _completionCancellations.Count > 0;
        }
    }

    private void Publish(SchedulerRuntimeSnapshot snapshot)
    {
        lock (_snapshotSync)
        {
            _current = snapshot;
        }

        Changed?.Invoke(this, snapshot);
    }

    private static bool IsTerminal(DownloadState state)
        => state is DownloadState.Completed or DownloadState.Failed or DownloadState.Cancelled;

    private static string BuildStatus(int activeSchedules, int activeQueues)
        => activeSchedules == 0
            ? "No schedule is currently active."
            : $"{activeSchedules} schedule{(activeSchedules == 1 ? string.Empty : "s")} active across {activeQueues} queue{(activeQueues == 1 ? string.Empty : "s")}.";

    private sealed record ScheduleDefinitionRuntime(
        QueueScheduleDefinition Schedule,
        string ScheduleKey);

    private sealed class ActiveScheduleRun(
        string runId,
        QueueScheduleDefinition schedule,
        string scheduleKey,
        DateTimeOffset windowStartUtc,
        DateTimeOffset definitionCapturedUtc,
        HashSet<string> downloadIds)
    {
        public string RunId { get; } = runId;

        public QueueScheduleDefinition Schedule { get; } = schedule;

        public string ScheduleKey { get; } = scheduleKey;

        public DateTimeOffset WindowStartUtc { get; } = windowStartUtc;

        public DateTimeOffset DefinitionCapturedUtc { get; } = definitionCapturedUtc;

        public HashSet<string> DownloadIds { get; } = downloadIds;

        public bool CompletionStarted { get; set; }

        public ScheduleRuntimeRunState ToState()
            => new(
                RunId,
                Schedule.Id,
                ScheduleKey,
                Schedule.QueueId,
                WindowStartUtc,
                DefinitionCapturedUtc,
                DownloadIds.ToArray(),
                CompletionStarted);
    }

    private sealed class InMemorySchedulerStateStore : ISchedulerStateStore
    {
        private SchedulerRuntimeState _state = SchedulerRuntimeState.Empty;

        public Task<SchedulerRuntimeState> LoadAsync(CancellationToken cancellationToken = default)
            => Task.FromResult(_state);

        public Task SaveAsync(SchedulerRuntimeState state, CancellationToken cancellationToken = default)
        {
            _state = state.Normalize();
            return Task.CompletedTask;
        }
    }

    private sealed class NoOpCompletionActionService : ICompletionActionService
    {
        public IReadOnlyList<CompletionActionCapability> GetCapabilities()
            => [new(ScheduleCompletionActionKind.None, true, "No action")];

        public Task<CompletionActionResult> ExecuteAsync(
            ScheduleCompletionAction action,
            CancellationToken cancellationToken = default)
            => Task.FromResult(new CompletionActionResult(action.Kind, true, "Completion action skipped in compatibility mode."));
    }

    private sealed class NoOpAntivirusScanner : IAntivirusScanner
    {
        public bool IsAvailable(AntivirusScanSettings settings) => false;

        public Task<AntivirusScanResult> ScanAsync(
            string filePath,
            AntivirusScanSettings settings,
            CancellationToken cancellationToken = default)
            => Task.FromResult(new AntivirusScanResult(filePath, false, null, "Antivirus scanning is unavailable."));
    }
}
