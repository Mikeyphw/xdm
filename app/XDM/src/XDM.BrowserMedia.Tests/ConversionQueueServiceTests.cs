using XDM.Media;

namespace XDM.BrowserMedia.Tests;

public sealed class ConversionQueueServiceTests
{
    [Fact]
    public async Task ProcessesQueuedJobsSeriallyAndPublishesProgress()
    {
        RecordingConversionService conversion = new();
        using ConversionQueueService queue = new(conversion);
        string first = queue.Enqueue(new ConversionRequest("one.mp4", "one.converted.mp4", "mp4-copy"));
        string second = queue.Enqueue(new ConversionRequest("two.mp4", "two.converted.mp4", "mp4-copy"));

        await WaitUntilAsync(() => queue.Current.Jobs.All(static job => job.State == ConversionJobState.Completed));

        Assert.Equal(1, conversion.MaximumConcurrentCalls);
        Assert.Equal(new[] { "one.mp4", "two.mp4" }, conversion.Sources);
        Assert.All(queue.Current.Jobs, static job =>
        {
            Assert.True(job.ProgressFraction.HasValue);
            Assert.Equal(1d, job.ProgressFraction.Value);
        });
        Assert.Contains(queue.Current.Jobs, job => string.Equals(job.Id, first, StringComparison.Ordinal));
        Assert.Contains(queue.Current.Jobs, job => string.Equals(job.Id, second, StringComparison.Ordinal));
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
                progress?.Report(new ConversionProgress(ConversionJobState.Converting, "working", 0.5));
                await Task.Delay(40, cancellationToken);
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
