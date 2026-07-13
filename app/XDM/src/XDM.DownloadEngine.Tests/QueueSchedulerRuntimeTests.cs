using Microsoft.Extensions.Logging.Abstractions;
using XDM.Core.Queues;
using XDM.Core.Scheduling;
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

    [Fact]
    public async Task StartsMultipleSchedulesForIndependentQueues()
    {
        ApplicationSettings defaults = ApplicationSettings.CreateDefault();
        ApplicationSettings configured = defaults with
        {
            Queues =
            [
                new DownloadQueueDefinition("first", "First", 1, 0),
                new DownloadQueueDefinition("second", "Second", 1, 0)
            ],
            Schedules =
            [
                new QueueScheduleDefinition(
                    "first-schedule",
                    "First",
                    true,
                    "first",
                    new TimeOnly(0, 0),
                    new TimeOnly(23, 59),
                    WeekDays.EveryDay,
                    MissedRunPolicy.Skip,
                    ScheduleCompletionAction.None),
                new QueueScheduleDefinition(
                    "second-schedule",
                    "Second",
                    true,
                    "second",
                    new TimeOnly(0, 0),
                    new TimeOnly(23, 59),
                    WeekDays.EveryDay,
                    MissedRunPolicy.Skip,
                    ScheduleCompletionAction.None)
            ]
        };
        RecordingDownloadManager manager = new();
        MutableSettingsService settings = new(configured);
        await using QueueSchedulerRuntime runtime = new(
            manager,
            settings,
            NullLogger<QueueSchedulerRuntime>.Instance);

        await runtime.InitializeAsync();

        Assert.Contains("first", manager.StartedQueues);
        Assert.Contains("second", manager.StartedQueues);
    }

    private static ApplicationSettings CreateSettings(bool enabled)
    {
        ApplicationSettings defaults = ApplicationSettings.CreateDefault();
        QueueScheduleDefinition schedule = new(
            "night-schedule",
            "Night",
            enabled,
            "night",
            new TimeOnly(0, 0),
            new TimeOnly(23, 59),
            WeekDays.EveryDay,
            MissedRunPolicy.Skip,
            ScheduleCompletionAction.None);
        return defaults with
        {
            Queues = [new DownloadQueueDefinition("night", "Night", 2, 0)],
            Scheduler = new DownloadSchedulerSettings(
                enabled,
                "night",
                schedule.StartTime,
                schedule.EndTime,
                schedule.Days),
            Schedules = [schedule]
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

        public int UndoableRemovalCount => 0;

        public List<string> StartedQueues { get; } = [];

        public List<string> StoppedQueues { get; } = [];

        public Task InitializeAsync(CancellationToken cancellationToken = default) => Task.CompletedTask;

        public Task<DownloadShutdownReport> PrepareForShutdownAsync(
            CancellationToken cancellationToken = default)
        {
            DateTimeOffset now = DateTimeOffset.UtcNow;
            return Task.FromResult(new DownloadShutdownReport(
                [],
                0,
                0,
                [],
                now,
                now));
        }

        public Task<string> AddAsync(DownloadRequest request, CancellationToken cancellationToken = default)
            => Task.FromResult(Guid.NewGuid().ToString("N"));

        public Task<DownloadVerificationResult> VerifyAsync(
            string downloadId,
            CancellationToken cancellationToken = default)
            => Task.FromResult(new DownloadVerificationResult(
                downloadId,
                string.Empty,
                DownloadChecksumService.Sha256,
                string.Empty,
                null,
                true,
                0,
                "Verified"));

        public Task<DownloadRepairResult> RepairAsync(
            string downloadId,
            CancellationToken cancellationToken = default)
            => Task.FromResult(new DownloadRepairResult(downloadId, false, null, "No repair required"));

        public Task<IReadOnlyList<string>> AddMetalinkAsync(
            Stream stream,
            string destinationDirectory,
            string? queueId = null,
            CancellationToken cancellationToken = default)
            => Task.FromResult<IReadOnlyList<string>>([]);

        public Task PauseAsync(string downloadId, CancellationToken cancellationToken = default) => Task.CompletedTask;

        public Task ResumeAsync(string downloadId, CancellationToken cancellationToken = default) => Task.CompletedTask;

        public Task CancelAsync(string downloadId, CancellationToken cancellationToken = default) => Task.CompletedTask;

        public Task RetryAsync(string downloadId, CancellationToken cancellationToken = default) => Task.CompletedTask;

        public Task SetPriorityAsync(
            string downloadId,
            XDM.Core.Downloads.DownloadPriority priority,
            CancellationToken cancellationToken = default)
            => Task.CompletedTask;

        public Task RemoveAsync(string downloadId, bool deletePartialFile = false, CancellationToken cancellationToken = default)
            => Task.CompletedTask;

        public Task<string?> UndoLastRemovalAsync(CancellationToken cancellationToken = default)
            => Task.FromResult<string?>(null);

        public Task DeleteAsync(
            string downloadId,
            XDM.Core.Downloads.DownloadDeletionScope scope,
            CancellationToken cancellationToken = default)
            => Task.CompletedTask;

        public Task RelocateAsync(
            string downloadId,
            string destinationPath,
            bool overwrite = false,
            CancellationToken cancellationToken = default)
            => Task.CompletedTask;

        public Task<string> RedownloadAsync(
            string downloadId,
            DuplicateFileBehavior duplicateBehavior = DuplicateFileBehavior.AutoRename,
            CancellationToken cancellationToken = default)
            => Task.FromResult(Guid.NewGuid().ToString("N"));

        public Task RefreshSourceAsync(
            string downloadId,
            Uri source,
            Uri? sourcePage = null,
            CancellationToken cancellationToken = default)
            => Task.CompletedTask;

        public Task SetTagsAsync(
            string downloadId,
            IReadOnlyList<string> tags,
            CancellationToken cancellationToken = default)
            => Task.CompletedTask;

        public Task SetArchivedAsync(
            string downloadId,
            bool archived,
            CancellationToken cancellationToken = default)
            => Task.CompletedTask;

        public Task RelinkAsync(
            string downloadId,
            string existingPath,
            CancellationToken cancellationToken = default)
            => Task.CompletedTask;

        public Task<int> PruneHistoryAsync(CancellationToken cancellationToken = default)
            => Task.FromResult(0);

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
