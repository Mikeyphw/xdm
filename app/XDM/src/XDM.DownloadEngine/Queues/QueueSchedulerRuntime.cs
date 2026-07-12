using Microsoft.Extensions.Logging;
using XDM.Core.Abstractions;
using XDM.Core.Downloads;
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
    private readonly SemaphoreSlim _evaluationGate = new(1, 1);
    private readonly Dictionary<string, ActiveScheduleRun> _runs = new(StringComparer.Ordinal);
    private readonly HashSet<string> _managedQueues = new(StringComparer.Ordinal);
    private readonly object _snapshotSync = new();
    private SchedulerRuntimeState _state = SchedulerRuntimeState.Empty;
    private SchedulerRuntimeSnapshot _current = SchedulerRuntimeSnapshot.Empty;
    private CancellationTokenSource? _lifetimeCancellation;
    private CancellationTokenSource? _pendingActionCancellation;
    private Task? _loopTask;
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
        ILogger<QueueSchedulerRuntime> logger)
    {
        _downloadManager = downloadManager;
        _settingsService = settingsService;
        _applicationState = applicationState;
        _stateStore = stateStore;
        _completionActions = completionActions;
        _antivirusScanner = antivirusScanner;
        _logger = logger;
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
            IReadOnlyList<QueueScheduleDefinition> schedules = settings.Schedules ?? [];
            Dictionary<string, DateTimeOffset> lastStarts = _state.LastStartedWindows
                .ToDictionary(static pair => pair.Key, static pair => pair.Value, StringComparer.Ordinal);
            QueueScheduleDefinition[] enabledSchedules = schedules
                .Where(static item => item.Enabled)
                .ToArray();
            HashSet<string> enabledScheduleIds = enabledSchedules
                .Select(static schedule => schedule.Id)
                .ToHashSet(StringComparer.Ordinal);
            foreach (string disabledRunId in _runs.Keys
                .Where(id => !enabledScheduleIds.Contains(id))
                .ToArray())
            {
                _runs.Remove(disabledRunId);
            }

            HashSet<string> inWindowScheduleIds = new(StringComparer.Ordinal);
            foreach (QueueScheduleDefinition schedule in enabledSchedules)
            {
                DateTimeOffset? currentWindow = ScheduleWindowCalculator.GetCurrentWindowStart(
                    schedule,
                    now,
                    TimeZoneInfo.Local);
                DateTimeOffset? missedWindow = null;
                if (currentWindow is null
                    && schedule.MissedRunPolicy == MissedRunPolicy.RunImmediately
                    && _state.LastEvaluationUtc != DateTimeOffset.MinValue)
                {
                    missedWindow = ScheduleWindowCalculator.GetLatestMissedStart(
                        schedule,
                        _state.LastEvaluationUtc,
                        now,
                        TimeZoneInfo.Local);
                }

                DateTimeOffset? candidateWindow = currentWindow ?? missedWindow;
                bool alreadyStarted = candidateWindow is not null
                    && lastStarts.TryGetValue(schedule.Id, out DateTimeOffset previousStart)
                    && previousStart >= candidateWindow.Value;
                if (candidateWindow is not null && !alreadyStarted)
                {
                    await StartScheduleRunAsync(schedule, cancellationToken).ConfigureAwait(false);
                    lastStarts[schedule.Id] = candidateWindow.Value;
                }

                if (currentWindow is not null)
                {
                    inWindowScheduleIds.Add(schedule.Id);
                }
            }

            foreach (string emptyExpiredRunId in _runs
                .Where(pair => pair.Value.DownloadIds.Count == 0 && !inWindowScheduleIds.Contains(pair.Key))
                .Select(static pair => pair.Key)
                .ToArray())
            {
                _runs.Remove(emptyExpiredRunId);
            }

            EvaluateCompletedRuns(
                settings,
                inWindowScheduleIds,
                cancellationToken);

            HashSet<string> activeScheduleIds = new(StringComparer.Ordinal);
            HashSet<string> desiredQueues = new(StringComparer.Ordinal);
            foreach (QueueScheduleDefinition schedule in enabledSchedules)
            {
                if (inWindowScheduleIds.Contains(schedule.Id) || _runs.ContainsKey(schedule.Id))
                {
                    activeScheduleIds.Add(schedule.Id);
                    desiredQueues.Add(schedule.QueueId);
                }
            }

            await SynchronizeManagedQueuesAsync(desiredQueues, cancellationToken).ConfigureAwait(false);

            _state = new SchedulerRuntimeState(now, lastStarts);
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
        CancellationTokenSource? cancellation = _pendingActionCancellation;
        if (cancellation is null || cancellation.IsCancellationRequested)
        {
            return false;
        }

        cancellation.Cancel();
        return true;
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
        _applicationState.Changed -= OnApplicationStateChanged;
        _pendingActionCancellation?.Cancel();
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

        _pendingActionCancellation?.Dispose();
        _lifetimeCancellation?.Dispose();
        _evaluationGate.Dispose();
        GC.SuppressFinalize(this);
    }

    private async Task RunLoopAsync(CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested)
        {
            NextEvaluationAt = DateTimeOffset.UtcNow.Add(EvaluationInterval);
            await Task.Delay(EvaluationInterval, cancellationToken).ConfigureAwait(false);
            await EvaluateAsync(cancellationToken).ConfigureAwait(false);
        }
    }

    private async Task StartScheduleRunAsync(
        QueueScheduleDefinition schedule,
        CancellationToken cancellationToken)
    {
        ApplicationSnapshot snapshot = _applicationState.Current;
        HashSet<string> tracked = snapshot.Downloads
            .Where(download => string.Equals(download.QueueId, schedule.QueueId, StringComparison.Ordinal)
                && !IsTerminal(download.State))
            .Select(static download => download.Id)
            .ToHashSet(StringComparer.Ordinal);
        _runs[schedule.Id] = new ActiveScheduleRun(schedule, tracked);
        await _downloadManager.StartQueueAsync(schedule.QueueId, cancellationToken).ConfigureAwait(false);
        _managedQueues.Add(schedule.QueueId);
        QueueStarted(_logger, schedule.QueueId, null);
    }

    private async Task SynchronizeManagedQueuesAsync(
        HashSet<string> desiredQueues,
        CancellationToken cancellationToken)
    {
        foreach (string queueId in desiredQueues)
        {
            if (_managedQueues.Add(queueId))
            {
                await _downloadManager.StartQueueAsync(queueId, cancellationToken).ConfigureAwait(false);
                QueueStarted(_logger, queueId, null);
            }
        }

        string[] noLongerDesired = _managedQueues
            .Where(queueId => !desiredQueues.Contains(queueId))
            .ToArray();
        foreach (string queueId in noLongerDesired)
        {
            await _downloadManager.StopQueueAsync(queueId, cancellationToken).ConfigureAwait(false);
            _managedQueues.Remove(queueId);
            QueueStopped(_logger, queueId, null);
        }
    }

    private void EvaluateCompletedRuns(
        ApplicationSettings settings,
        HashSet<string> inWindowScheduleIds,
        CancellationToken cancellationToken)
    {
        ApplicationSnapshot snapshot = _applicationState.Current;
        Dictionary<string, DownloadSnapshot> downloads = snapshot.Downloads
            .ToDictionary(static download => download.Id, StringComparer.Ordinal);
        List<ActiveScheduleRun> completed = [];
        foreach (ActiveScheduleRun run in _runs.Values)
        {
            if (inWindowScheduleIds.Contains(run.Schedule.Id))
            {
                continue;
            }

            foreach (DownloadSnapshot download in snapshot.Downloads.Where(download =>
                string.Equals(download.QueueId, run.Schedule.QueueId, StringComparison.Ordinal)
                && !IsTerminal(download.State)))
            {
                run.DownloadIds.Add(download.Id);
            }

            if (run.DownloadIds.Count == 0)
            {
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
            _runs.Remove(run.Schedule.Id);
            DownloadSnapshot[] completedDownloads = run.DownloadIds
                .Select(id => downloads.GetValueOrDefault(id))
                .Where(static download => download?.State == DownloadState.Completed)
                .Cast<DownloadSnapshot>()
                .ToArray();
            _ = ExecuteCompletionWorkflowAsync(
                run.Schedule,
                completedDownloads,
                settings.Antivirus ?? AntivirusScanSettings.Disabled,
                cancellationToken);
        }
    }

    private async Task ExecuteCompletionWorkflowAsync(
        QueueScheduleDefinition schedule,
        IReadOnlyList<DownloadSnapshot> completedDownloads,
        AntivirusScanSettings antivirus,
        CancellationToken schedulerCancellation)
    {
        CancellationTokenSource cancellation = CancellationTokenSource.CreateLinkedTokenSource(schedulerCancellation);
        CancellationTokenSource? previous = Interlocked.Exchange(ref _pendingActionCancellation, cancellation);
        previous?.Cancel();
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

            ScheduleCompletionAction action = schedule.CompletionAction.Normalize();
            if (action.Kind == ScheduleCompletionActionKind.None)
            {
                PublishStatus($"Schedule '{schedule.Name}' completed.");
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
                        schedule.Id,
                        schedule.Name,
                        action.Kind,
                        executeAt,
                        remaining,
                        remaining == 0
                            ? $"Executing {action.Kind}."
                            : $"{action.Kind} in {remaining} second{(remaining == 1 ? string.Empty : "s")}."));
                    return Task.CompletedTask;
                },
                cancellation.Token).ConfigureAwait(false);
            CompletionActionResult actionResult = await _completionActions
                .ExecuteAsync(action, cancellation.Token)
                .ConfigureAwait(false);
            ActionFinished(_logger, schedule.Id, actionResult.Message, null);
            PublishStatus(actionResult.Message);
        }
        catch (OperationCanceledException) when (cancellation.IsCancellationRequested)
        {
            PublishStatus($"Completion action for '{schedule.Name}' was cancelled.");
        }
        catch (FileNotFoundException exception)
        {
            PublishStatus($"Completion action failed: {exception.Message}");
        }
        catch (InvalidOperationException exception)
        {
            PublishStatus($"Completion action failed: {exception.Message}");
        }
        finally
        {
            Interlocked.CompareExchange(ref _pendingActionCancellation, null, cancellation);
            cancellation.Dispose();
        }
    }

    private void OnSettingsChanged(object? sender, ApplicationSettings settings)
        => _ = EvaluateSafelyAsync();

    private void OnApplicationStateChanged(object? sender, ApplicationSnapshot snapshot)
        => _ = EvaluateSafelyAsync();

    private async Task EvaluateSafelyAsync()
    {
        try
        {
            await EvaluateAsync().ConfigureAwait(false);
        }
        catch (ObjectDisposedException)
        {
        }
        catch (OperationCanceledException)
        {
        }
        catch (IOException exception)
        {
            PublishStatus($"Scheduler state could not be saved: {exception.Message}");
        }
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
            PendingAction = null
        });
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

    private sealed class ActiveScheduleRun(
        QueueScheduleDefinition schedule,
        HashSet<string> downloadIds)
    {
        public QueueScheduleDefinition Schedule { get; } = schedule;

        public HashSet<string> DownloadIds { get; } = downloadIds;
    }

    private sealed class InMemorySchedulerStateStore : ISchedulerStateStore
    {
        private SchedulerRuntimeState _state = SchedulerRuntimeState.Empty;

        public Task<SchedulerRuntimeState> LoadAsync(CancellationToken cancellationToken = default)
            => Task.FromResult(_state);

        public Task SaveAsync(SchedulerRuntimeState state, CancellationToken cancellationToken = default)
        {
            _state = state;
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
