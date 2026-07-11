using Microsoft.Extensions.Logging;
using XDM.Core.Scheduling;
using XDM.Core.Settings;

namespace XDM.DownloadEngine.Queues;

public interface IQueueSchedulerRuntime : IDisposable, IAsyncDisposable
{
    bool IsRunning { get; }

    DateTimeOffset? NextEvaluationAt { get; }

    Task InitializeAsync(CancellationToken cancellationToken = default);

    Task EvaluateAsync(CancellationToken cancellationToken = default);
}

public sealed class QueueSchedulerRuntime : IQueueSchedulerRuntime
{
    private static readonly TimeSpan EvaluationInterval = TimeSpan.FromSeconds(15);
    private static readonly Action<ILogger, string, Exception?> QueueStarted =
        LoggerMessage.Define<string>(LogLevel.Information, new EventId(5101, nameof(QueueStarted)), "Scheduled queue {QueueId} started.");
    private static readonly Action<ILogger, string, Exception?> QueueStopped =
        LoggerMessage.Define<string>(LogLevel.Information, new EventId(5102, nameof(QueueStopped)), "Scheduled queue {QueueId} stopped.");

    private readonly IDownloadManager _downloadManager;
    private readonly ISettingsService _settingsService;
    private readonly ILogger<QueueSchedulerRuntime> _logger;
    private readonly SemaphoreSlim _evaluationGate = new(1, 1);
    private CancellationTokenSource? _lifetimeCancellation;
    private Task? _loopTask;
    private string? _managedQueueId;
    private bool _managedQueueRunning;
    private bool _disposed;

    public QueueSchedulerRuntime(
        IDownloadManager downloadManager,
        ISettingsService settingsService,
        ILogger<QueueSchedulerRuntime> logger)
    {
        _downloadManager = downloadManager;
        _settingsService = settingsService;
        _logger = logger;
    }

    public bool IsRunning => _loopTask is { IsCompleted: false };

    public DateTimeOffset? NextEvaluationAt { get; private set; }

    public async Task InitializeAsync(CancellationToken cancellationToken = default)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        if (_loopTask is { IsCompleted: false })
        {
            return;
        }

        _lifetimeCancellation = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        _settingsService.Changed += OnSettingsChanged;
        await EvaluateAsync(cancellationToken).ConfigureAwait(false);
        _loopTask = RunLoopAsync(_lifetimeCancellation.Token);
    }

    public async Task EvaluateAsync(CancellationToken cancellationToken = default)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        await _evaluationGate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            ApplicationSettings settings = _settingsService.Current;
            DownloadSchedulerSettings scheduler = settings.Scheduler;
            string queueId = scheduler.QueueId;

            if (_managedQueueId is not null
                && !string.Equals(_managedQueueId, queueId, StringComparison.Ordinal)
                && _managedQueueRunning)
            {
                await _downloadManager.StopQueueAsync(_managedQueueId, cancellationToken).ConfigureAwait(false);
                _managedQueueRunning = false;
            }

            _managedQueueId = queueId;
            bool shouldRun = scheduler.Enabled && IsInsideSchedule(scheduler, DateTimeOffset.Now);
            if (shouldRun && !_managedQueueRunning)
            {
                await _downloadManager.StartQueueAsync(queueId, cancellationToken).ConfigureAwait(false);
                _managedQueueRunning = true;
                QueueStarted(_logger, queueId, null);
            }
            else if (!shouldRun && _managedQueueRunning)
            {
                await _downloadManager.StopQueueAsync(queueId, cancellationToken).ConfigureAwait(false);
                _managedQueueRunning = false;
                QueueStopped(_logger, queueId, null);
            }

            NextEvaluationAt = DateTimeOffset.Now.Add(EvaluationInterval);
        }
        finally
        {
            _evaluationGate.Release();
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
        _evaluationGate.Dispose();
        GC.SuppressFinalize(this);
    }

    private async Task RunLoopAsync(CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested)
        {
            NextEvaluationAt = DateTimeOffset.Now.Add(EvaluationInterval);
            await Task.Delay(EvaluationInterval, cancellationToken).ConfigureAwait(false);
            await EvaluateAsync(cancellationToken).ConfigureAwait(false);
        }
    }

    private void OnSettingsChanged(object? sender, ApplicationSettings settings)
        => _ = EvaluateSettingsChangeAsync();

    private async Task EvaluateSettingsChangeAsync()
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
    }

    private static bool IsInsideSchedule(DownloadSchedulerSettings scheduler, DateTimeOffset now)
    {
        DownloadSchedule schedule = new(scheduler.StartTime, scheduler.EndTime, scheduler.Days);
        return schedule.IsActiveAt(now, TimeZoneInfo.Local);
    }
}
