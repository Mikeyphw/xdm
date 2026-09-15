using XDM.Media;

namespace XDM.BrowserMedia.Tests;

public sealed class ConversionQueueServiceTests
{
    private static readonly string[] ExpectedSources = ["one.mp4", "two.mp4"];
    [Fact]
    public async Task ProcessesQueuedJobsSeriallyAndPublishesProgress()
    {
        RecordingConversionService conversion = new();
        using ConversionQueueService queue = new(conversion);
        string first = queue.Enqueue(new ConversionRequest("one.mp4", "one.converted.mp4", "mp4-copy"));
        string second = queue.Enqueue(new ConversionRequest("two.mp4", "two.converted.mp4", "mp4-copy"));

        await WaitUntilAsync(() => queue.Current.Jobs.All(static job => job.State == ConversionJobState.Completed));

        Assert.Equal(1, conversion.MaximumConcurrentCalls);
        Assert.Equal(ExpectedSources, conversion.Sources);
        Assert.All(queue.Current.Jobs, static job =>
        {
            Assert.True(job.ProgressFraction.HasValue);
            Assert.Equal(1d, job.ProgressFraction.Value);
        });
        Assert.Contains(queue.Current.Jobs, job => string.Equals(job.Id, first, StringComparison.Ordinal));
        Assert.Contains(queue.Current.Jobs, job => string.Equals(job.Id, second, StringComparison.Ordinal));
    }

    [Fact]
    public async Task ChangedSubscriberFailureDoesNotStopWorker()
    {
        RecordingConversionService conversion = new();
        using ConversionQueueService queue = new(conversion);
        queue.Changed += (_, _) => throw new InvalidOperationException("subscriber failed");

        string jobId = queue.Enqueue(new ConversionRequest("one.mp4", "one.converted.mp4", "mp4-copy"));
        await WaitUntilAsync(() => queue.Current.Jobs.Any(job => job.Id == jobId && job.State == ConversionJobState.Completed));

        Assert.Single(conversion.Sources);
        Assert.Equal(ConversionJobState.Completed, Assert.Single(queue.Current.Jobs).State);
    }

    [Fact]
    public async Task ProgressDetailsAreRetainedInSnapshots()
    {
        RecordingConversionService conversion = new()
        {
            ProgressToReport = new ConversionProgress(
                ConversionJobState.Converting,
                "working",
                0.5,
                TimeSpan.FromSeconds(12),
                4096,
                "2.0x")
        };
        using ConversionQueueService queue = new(conversion);

        string jobId = queue.Enqueue(new ConversionRequest("one.mp4", "one.converted.mp4", "mp4-copy"));
        await WaitUntilAsync(() => queue.Current.Jobs.Any(job => job.Id == jobId && job.State == ConversionJobState.Completed));

        ConversionJobSnapshot job = Assert.Single(queue.Current.Jobs);
        Assert.Equal(TimeSpan.FromSeconds(12), job.ProcessedDuration);
        Assert.Equal("2.0x", job.Speed);
    }

    [Fact]
    public async Task BoundsTerminalHistory()
    {
        RecordingConversionService conversion = new() { Delay = TimeSpan.Zero };
        using ConversionQueueService queue = new(conversion);
        for (int index = 0; index < 205; index++)
        {
            queue.Enqueue(new ConversionRequest($"source-{index}.mp4", $"dest-{index}.mp4", "mp4-copy"));
        }

        await WaitUntilAsync(() => queue.Current.Jobs.Count == 200
            && queue.Current.Jobs.All(static job => job.State == ConversionJobState.Completed));

        Assert.DoesNotContain(queue.Current.Jobs, static job => job.Request.SourcePath == "source-0.mp4");
        Assert.Contains(queue.Current.Jobs, static job => job.Request.SourcePath == "source-204.mp4");
    }

    [Fact]
    public async Task CancelsActiveJobAndContinuesWithLaterJobs()
    {
        CancellableConversionService conversion = new();
        using ConversionQueueService queue = new(conversion);
        string first = queue.Enqueue(new ConversionRequest("one.mp4", "one.converted.mp4", "mp4-copy"));
        string second = queue.Enqueue(new ConversionRequest("two.mp4", "two.converted.mp4", "mp4-copy"));
        await WaitUntilAsync(() => queue.Current.ActiveJobId == first);

        Assert.True(queue.Cancel(first));
        await WaitUntilAsync(() => queue.Current.Jobs.Any(job => job.Id == second && job.State == ConversionJobState.Completed));

        ConversionJobSnapshot firstJob = Assert.Single(queue.Current.Jobs, job => job.Id == first);
        ConversionJobSnapshot secondJob = Assert.Single(queue.Current.Jobs, job => job.Id == second);
        Assert.Equal(ConversionJobState.Cancelled, firstJob.State);
        Assert.Equal(ConversionJobState.Completed, secondJob.State);
    }

    private static async Task WaitUntilAsync(Func<bool> condition)
    {
        using CancellationTokenSource timeout = new(TimeSpan.FromSeconds(5));
        while (!condition())
        {
            await Task.Delay(20, timeout.Token);
        }
    }

    private sealed class RecordingConversionService : IConversionService
    {
        private int _activeCalls;

        public IReadOnlyList<ConversionPreset> Presets { get; } =
            [new("mp4-copy", "MP4 copy", "test", ConversionKind.Remux, ".mp4")];

        public List<string> Sources { get; } = [];

        public ConversionProgress ProgressToReport { get; init; } = new(ConversionJobState.Converting, "working", 0.5);

        public TimeSpan Delay { get; init; } = TimeSpan.FromMilliseconds(40);

        public int MaximumConcurrentCalls { get; private set; }

        public Task<ExternalToolHealth> GetHealthAsync(CancellationToken cancellationToken = default)
            => Task.FromResult(new ExternalToolHealth("test", true, "test", "test", "ok"));

        public Task<MediaInspection> InspectAsync(string sourcePath, CancellationToken cancellationToken = default)
            => Task.FromResult(new MediaInspection(TimeSpan.FromSeconds(1), "mp4", "h264", "aac", true, true));

        public async Task<ConversionResult> ConvertAsync(
            ConversionRequest request,
            IProgress<ConversionProgress>? progress = null,
            CancellationToken cancellationToken = default)
        {
            int active = Interlocked.Increment(ref _activeCalls);
            MaximumConcurrentCalls = Math.Max(MaximumConcurrentCalls, active);
            Sources.Add(request.SourcePath);
            try
            {
                progress?.Report(ProgressToReport);
                if (Delay > TimeSpan.Zero)
                {
                    await Task.Delay(Delay, cancellationToken);
                }
                return new ConversionResult(
                    request.SourcePath,
                    request.DestinationPath,
                    Presets[0],
                    100,
                    TimeSpan.FromMilliseconds(40));
            }
            finally
            {
                Interlocked.Decrement(ref _activeCalls);
            }
        }
    }

    private sealed class CancellableConversionService : IConversionService
    {
        private int _calls;

        public IReadOnlyList<ConversionPreset> Presets { get; } =
            [new("mp4-copy", "MP4 copy", "test", ConversionKind.Remux, ".mp4")];

        public Task<ExternalToolHealth> GetHealthAsync(CancellationToken cancellationToken = default)
            => Task.FromResult(new ExternalToolHealth("test", true, "test", "test", "ok"));

        public Task<MediaInspection> InspectAsync(string sourcePath, CancellationToken cancellationToken = default)
            => Task.FromResult(new MediaInspection(TimeSpan.FromSeconds(1), "mp4", "h264", "aac", true, true));

        public async Task<ConversionResult> ConvertAsync(
            ConversionRequest request,
            IProgress<ConversionProgress>? progress = null,
            CancellationToken cancellationToken = default)
        {
            int call = Interlocked.Increment(ref _calls);
            progress?.Report(new ConversionProgress(ConversionJobState.Converting, "working", 0.25));
            if (call == 1)
            {
                await Task.Delay(Timeout.InfiniteTimeSpan, cancellationToken);
            }

            return new ConversionResult(
                request.SourcePath,
                request.DestinationPath,
                Presets[0],
                100,
                TimeSpan.Zero);
        }
    }
}
