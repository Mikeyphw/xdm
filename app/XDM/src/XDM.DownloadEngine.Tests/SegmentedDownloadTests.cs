using System.Collections.Concurrent;
using System.Net;
using System.Net.Http.Headers;
using Microsoft.Extensions.Logging.Abstractions;
using XDM.Core.Downloads;
using XDM.Core.Persistence;
using XDM.Core.Settings;
using XDM.Core.State;

namespace XDM.DownloadEngine.Tests;

public sealed class SegmentedDownloadTests
{
    [Fact]
    public void PlanCoversFileWithoutGapsOrOverlaps()
    {
        SegmentedDownloadPlan plan = SegmentedDownloadPlan.Create(10_003, 4);

        plan.Validate();
        Assert.Equal(4, plan.Segments.Count);
        Assert.Equal(10_003, plan.Segments.Sum(static segment => segment.Length));
        Assert.Equal(0, plan.Segments[0].Start);
        Assert.Equal(10_002, plan.Segments[^1].End);
    }

    [Fact]
    public async Task DownloadsConcurrentRangesAndMergesInOrder()
    {
        byte[] payload = CreatePayload(16_384);
        using TemporaryDirectory directory = new();
        SegmentedRangeHandler handler = new(payload);
        using HttpClient client = new(handler);
        ApplicationState state = new();
        using DownloadManager manager = CreateManager(client, state);

        string id = await manager.AddAsync(new DownloadRequest(
            new Uri("https://example.test/segmented.bin"),
            directory.Path,
            "segmented.bin",
            ConnectionCount: 4));

        DownloadSnapshot completed = await WaitForStateAsync(state, id, DownloadState.Completed);

        Assert.Equal(4, completed.ConnectionCount);
        Assert.Equal(payload, await File.ReadAllBytesAsync(completed.DestinationPath));
        Assert.Equal(4, handler.DataRanges.Count);
        Assert.False(Directory.Exists($"{completed.DestinationPath}.segments"));
    }

    [Fact]
    public async Task ResumesExistingSegmentCheckpoint()
    {
        byte[] payload = CreatePayload(8192);
        using TemporaryDirectory directory = new();
        string destination = Path.Combine(directory.Path, "resume-segments.bin");
        string segmentDirectory = $"{destination}.segments";
        Directory.CreateDirectory(segmentDirectory);
        SegmentedDownloadPlan plan = SegmentedDownloadPlan.Create(payload.Length, 4);
        DownloadSegment first = plan.Segments[0];
        int checkpointLength = checked((int)(first.Length / 2));
        await File.WriteAllBytesAsync(
            Path.Combine(segmentDirectory, "0000.part"),
            payload[..checkpointLength]);

        SegmentedRangeHandler handler = new(payload);
        using HttpClient client = new(handler);
        ApplicationState state = new();
        using DownloadManager manager = CreateManager(client, state);
        string id = await manager.AddAsync(new DownloadRequest(
            new Uri("https://example.test/resume-segments.bin"),
            directory.Path,
            "resume-segments.bin",
            ConnectionCount: 4));

        await WaitForStateAsync(state, id, DownloadState.Completed);

        Assert.Contains(
            handler.DataRanges,
            range => range.Start == checkpointLength && range.End == first.End);
        Assert.Equal(payload, await File.ReadAllBytesAsync(destination));
    }

    [Fact]
    public async Task FallsBackToSingleStreamWhenProbeRangeIsIgnored()
    {
        byte[] payload = CreatePayload(4096);
        using TemporaryDirectory directory = new();
        IgnoreProbeRangeHandler handler = new(payload);
        using HttpClient client = new(handler);
        ApplicationState state = new();
        using DownloadManager manager = CreateManager(client, state);

        string id = await manager.AddAsync(new DownloadRequest(
            new Uri("https://example.test/fallback.bin"),
            directory.Path,
            "fallback.bin",
            ConnectionCount: 4));

        DownloadSnapshot completed = await WaitForStateAsync(state, id, DownloadState.Completed);

        Assert.Equal(2, handler.RequestCount);
        Assert.Equal(payload, await File.ReadAllBytesAsync(completed.DestinationPath));
    }

    private static DownloadManager CreateManager(HttpClient client, ApplicationState state)
        => new(
            client,
            state,
            new InMemoryHistoryStore(),
            new TestSettingsService(),
            NullLogger<DownloadManager>.Instance,
            new FixedDiskSpaceProvider(long.MaxValue),
            new DownloadRetryPolicy(3, TimeSpan.FromMilliseconds(1), 0),
            new SegmentedDownloadOptions(4, 8, 1));

    private static async Task<DownloadSnapshot> WaitForStateAsync(
        ApplicationState state,
        string id,
        DownloadState expected)
    {
        using CancellationTokenSource timeout = new(TimeSpan.FromSeconds(10));
        while (!timeout.IsCancellationRequested)
        {
            DownloadSnapshot? snapshot = state.Current.Downloads.FirstOrDefault(item => item.Id == id);
            if (snapshot?.State == expected)
            {
                return snapshot;
            }

            if (snapshot?.State == DownloadState.Failed && expected != DownloadState.Failed)
            {
                throw new InvalidOperationException(snapshot.ErrorMessage);
            }

            await Task.Delay(20, timeout.Token);
        }

        throw new TimeoutException($"Download did not reach {expected}.");
    }

    private static byte[] CreatePayload(int length)
        => Enumerable.Range(0, length).Select(static value => (byte)(value % 251)).ToArray();

    private sealed class SegmentedRangeHandler(byte[] payload) : HttpMessageHandler
    {
        public ConcurrentBag<(long Start, long End)> DataRanges { get; } = [];

        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            RangeItemHeaderValue? requested = request.Headers.Range?.Ranges.SingleOrDefault();
            long start = requested?.From ?? 0;
            long end = requested?.To ?? payload.Length - 1;
            int length = checked((int)(end - start + 1));
            if (!(start == 0 && end == 0))
            {
                DataRanges.Add((start, end));
            }

            ByteArrayContent content = new(payload.AsSpan(checked((int)start), length).ToArray());
            HttpResponseMessage response = new(HttpStatusCode.PartialContent)
            {
                Content = content
            };
            response.Headers.ETag = new EntityTagHeaderValue("\"segmented-v1\"");
            response.Content.Headers.ContentLength = length;
            response.Content.Headers.ContentRange = new ContentRangeHeaderValue(start, end, payload.Length);
            return Task.FromResult(response);
        }
    }

    private sealed class IgnoreProbeRangeHandler(byte[] payload) : HttpMessageHandler
    {
        public int RequestCount { get; private set; }

        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            RequestCount++;
            ByteArrayContent content = new(payload);
            HttpResponseMessage response = new(HttpStatusCode.OK)
            {
                Content = content
            };
            response.Content.Headers.ContentLength = payload.Length;
            return Task.FromResult(response);
        }
    }

    private sealed class InMemoryHistoryStore : IDownloadHistoryStore
    {
        public Task<IReadOnlyList<PersistedDownload>> LoadAsync(CancellationToken cancellationToken = default)
            => Task.FromResult<IReadOnlyList<PersistedDownload>>([]);

        public Task SaveAsync(
            IReadOnlyCollection<PersistedDownload> downloads,
            CancellationToken cancellationToken = default)
            => Task.CompletedTask;
    }

    private sealed class TestSettingsService : ISettingsService
    {
        public ApplicationSettings Current { get; private set; } = ApplicationSettings.CreateDefault();

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

    private sealed class FixedDiskSpaceProvider(long availableBytes) : IDiskSpaceProvider
    {
        public long? GetAvailableBytes(string destinationPath) => availableBytes;
    }

    private sealed class TemporaryDirectory : IDisposable
    {
        public TemporaryDirectory()
        {
            Path = System.IO.Path.Combine(System.IO.Path.GetTempPath(), $"xdm-segmented-{Guid.NewGuid():N}");
            Directory.CreateDirectory(Path);
        }

        public string Path { get; }

        public void Dispose()
        {
            if (Directory.Exists(Path))
            {
                Directory.Delete(Path, recursive: true);
            }
        }
    }
}
