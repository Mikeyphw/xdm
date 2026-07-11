using Microsoft.Extensions.Logging.Abstractions;
using XDM.Core.Queues;
using XDM.Core.Settings;
using XDM.DownloadEngine.Queues;

namespace XDM.DownloadEngine.Tests;

public sealed class QueueSchedulerRuntimeTests
{
    [Fact]
    public async Task StartsAndStopsConfiguredQueue()
    {
        RecordingDownloadManager manager = new();
        MutableSettingsService settings = new(CreateSettings(enabled: true));
        await using QueueSchedulerRuntime runtime = new(
            manager,
            settings,
            NullLogger<QueueSchedulerRuntime>.Instance);

        await runtime.InitializeAsync();

        Assert.Contains("night", manager.StartedQueues);

        await settings.UpdateAsync(CreateSettings(enabled: false));
        await runtime.EvaluateAsync();

        Assert.Contains("night", manager.StoppedQueues);
    }

    private static ApplicationSettings CreateSettings(bool enabled)
    {
        ApplicationSettings defaults = ApplicationSettings.CreateDefault();
        return defaults with
        {
            Queues = [new DownloadQueueDefinition("night", "Night", 2, 0)],
            Scheduler = new DownloadSchedulerSettings(
                enabled,
                "night",
                new TimeOnly(0, 0),
                new TimeOnly(23, 59),
                XDM.Core.Scheduling.WeekDays.EveryDay)
        };
    }

    private sealed class MutableSettingsService(ApplicationSettings current) : ISettingsService
    {
        public ApplicationSettings Current { get; private set; } = current.Normalize();

        public event EventHandler<ApplicationSettings>? Changed;

        public Task InitializeAsync(CancellationToken cancellationToken = default)
            => Task.CompletedTask;

        public Task UpdateAsync(ApplicationSettings settings, CancellationToken cancellationToken = default)
        {
            Current = settings.Normalize();
            Changed?.Invoke(this, Current);
            return Task.CompletedTask;
        }
    }

    private sealed class RecordingDownloadManager : IDownloadManager
    {
        public event EventHandler<QueueRuntimeSnapshot>? QueueRuntimeChanged;

        public QueueRuntimeSnapshot QueueRuntime { get; private set; } = QueueRuntimeSnapshot.Empty;

        public List<string> StartedQueues { get; } = [];

        public List<string> StoppedQueues { get; } = [];

        public Task InitializeAsync(CancellationToken cancellationToken = default) => Task.CompletedTask;

        public Task<string> AddAsync(DownloadRequest request, CancellationToken cancellationToken = default)
            => Task.FromResult(Guid.NewGuid().ToString("N"));

        public Task PauseAsync(string downloadId, CancellationToken cancellationToken = default) => Task.CompletedTask;

        public Task ResumeAsync(string downloadId, CancellationToken cancellationToken = default) => Task.CompletedTask;

        public Task CancelAsync(string downloadId, CancellationToken cancellationToken = default) => Task.CompletedTask;

        public Task RetryAsync(string downloadId, CancellationToken cancellationToken = default) => Task.CompletedTask;

        public Task RemoveAsync(string downloadId, bool deletePartialFile = false, CancellationToken cancellationToken = default)
            => Task.CompletedTask;

        public Task StartQueueAsync(string queueId, CancellationToken cancellationToken = default)
        {
            StartedQueues.Add(queueId);
            QueueRuntime = new QueueRuntimeSnapshot(
                new HashSet<string>(StartedQueues, StringComparer.Ordinal),
                new Dictionary<string, int>(StringComparer.Ordinal),
                DateTimeOffset.UtcNow);
            QueueRuntimeChanged?.Invoke(this, QueueRuntime);
            return Task.CompletedTask;
        }

        public Task StopQueueAsync(string queueId, CancellationToken cancellationToken = default)
        {
            StoppedQueues.Add(queueId);
            return Task.CompletedTask;
        }

        public Task MoveToQueueAsync(
            string downloadId,
            string queueId,
            int? queueOrder = null,
            CancellationToken cancellationToken = default)
            => Task.CompletedTask;
    }
}
