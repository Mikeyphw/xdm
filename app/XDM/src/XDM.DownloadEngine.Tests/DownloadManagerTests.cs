using System.Net;
using System.Net.Http.Headers;
using Microsoft.Extensions.Logging.Abstractions;
using XDM.Core.Downloads;
using XDM.Core.Persistence;
using XDM.Core.Settings;
using XDM.Core.State;

namespace XDM.DownloadEngine.Tests;

public sealed class DownloadManagerTests
{
    [Fact]
    public async Task CompletesDownloadAndPublishesState()
    {
        byte[] payload = CreatePayload(4096, 251);
        using TemporaryDirectory directory = new();
        using HttpClient client = new(new RangeHandler(payload));
        ApplicationState state = new();
        InMemoryHistoryStore history = new();
        using DownloadManager manager = CreateManager(client, state, history);

        string id = await manager.AddAsync(new DownloadRequest(
            new Uri("https://example.test/payload.bin"),
            directory.Path,
            "payload.bin"));

        DownloadSnapshot completed = await WaitForStateAsync(state, id, DownloadState.Completed);

        Assert.Equal(payload.Length, completed.DownloadedBytes);
        Assert.Equal(payload, await File.ReadAllBytesAsync(Path.Combine(directory.Path, "payload.bin")));
    }

    [Fact]
    public async Task ResumesFromExistingPartialFile()
    {
        byte[] payload = CreatePayload(8192, 239);
        const int partialLength = 2048;
        using TemporaryDirectory directory = new();
        string destination = Path.Combine(directory.Path, "resume.bin");
        await File.WriteAllBytesAsync($"{destination}.part", payload[..partialLength]);

        RangeHandler handler = new(payload);
        using HttpClient client = new(handler);
        ApplicationState state = new();
        using DownloadManager manager = CreateManager(client, state, new InMemoryHistoryStore());

        string id = await manager.AddAsync(new DownloadRequest(
            new Uri("https://example.test/resume.bin"),
            directory.Path,
            "resume.bin"));

        await WaitForStateAsync(state, id, DownloadState.Completed);

        Assert.Equal(partialLength, handler.LastRangeStart);
        Assert.Equal(payload, await File.ReadAllBytesAsync(destination));
    }

    [Fact]
    public async Task RestartsSafelyWhenServerIgnoresRange()
    {
        byte[] payload = CreatePayload(4096, 193);
        using TemporaryDirectory directory = new();
        string destination = Path.Combine(directory.Path, "ignored-range.bin");
        await File.WriteAllBytesAsync($"{destination}.part", payload[..1024]);

        IgnoreRangeHandler handler = new(payload);
        using HttpClient client = new(handler);
        ApplicationState state = new();
        using DownloadManager manager = CreateManager(client, state, new InMemoryHistoryStore());

        string id = await manager.AddAsync(new DownloadRequest(
            new Uri("https://example.test/ignored-range.bin"),
            directory.Path,
            "ignored-range.bin"));

        await WaitForStateAsync(state, id, DownloadState.Completed);

        Assert.Equal(1024, handler.RequestedRangeStart);
        Assert.Equal(payload, await File.ReadAllBytesAsync(destination));
    }

    [Fact]
    public async Task UsesPersistedEntityTagAsIfRangeValidator()
    {
        byte[] payload = CreatePayload(4096, 181);
        const int partialLength = 1024;
        using TemporaryDirectory directory = new();
        string destination = Path.Combine(directory.Path, "validated.bin");
        await File.WriteAllBytesAsync($"{destination}.part", payload[..partialLength]);

        PersistedDownload persisted = new(
            "validated",
            new Uri("https://example.test/validated.bin"),
            destination,
            partialLength,
            payload.Length,
            DownloadState.Paused,
            DateTimeOffset.UtcNow,
            EntityTag: "\"stable-v1\"");
        InMemoryHistoryStore history = new([persisted]);
        RangeHandler handler = new(payload, "\"stable-v1\"");
        using HttpClient client = new(handler);
        ApplicationState state = new();
        using DownloadManager manager = CreateManager(client, state, history);

        await manager.InitializeAsync();
        await manager.ResumeAsync("validated");
        await WaitForStateAsync(state, "validated", DownloadState.Completed);

        Assert.Equal("\"stable-v1\"", handler.LastIfRangeEntityTag);
        Assert.Equal(payload, await File.ReadAllBytesAsync(destination));
    }

    [Fact]
    public async Task RejectsChangedEntityTagDuringResume()
    {
        byte[] payload = CreatePayload(4096, 173);
        const int partialLength = 1024;
        using TemporaryDirectory directory = new();
        string destination = Path.Combine(directory.Path, "changed.bin");
        string partialPath = $"{destination}.part";
        await File.WriteAllBytesAsync(partialPath, payload[..partialLength]);

        PersistedDownload persisted = new(
            "changed",
            new Uri("https://example.test/changed.bin"),
            destination,
            partialLength,
            payload.Length,
            DownloadState.Paused,
            DateTimeOffset.UtcNow,
            EntityTag: "\"old\"");
        InMemoryHistoryStore history = new([persisted]);
        RangeHandler handler = new(payload, "\"new\"");
        using HttpClient client = new(handler);
        ApplicationState state = new();
        using DownloadManager manager = CreateManager(client, state, history);

        await manager.InitializeAsync();
        await manager.ResumeAsync("changed");
        DownloadSnapshot failed = await WaitForStateAsync(state, "changed", DownloadState.Failed);

        Assert.Contains("entity tag changed", failed.ErrorMessage, StringComparison.OrdinalIgnoreCase);
        Assert.Equal(partialLength, new FileInfo(partialPath).Length);
        Assert.False(File.Exists(destination));
    }

    [Fact]
    public async Task RetriesTruncatedResponseAndResumesFromCheckpoint()
    {
        byte[] payload = CreatePayload(8192, 167);
        using TemporaryDirectory directory = new();
        TruncatedThenRangeHandler handler = new(payload);
        using HttpClient client = new(handler);
        ApplicationState state = new();
        using DownloadManager manager = CreateManager(
            client,
            state,
            new InMemoryHistoryStore(),
            retryPolicy: new DownloadRetryPolicy(3, TimeSpan.FromMilliseconds(1), 0));

        string id = await manager.AddAsync(new DownloadRequest(
            new Uri("https://example.test/retry.bin"),
            directory.Path,
            "retry.bin"));

        await WaitForStateAsync(state, id, DownloadState.Completed);

        Assert.Equal(2, handler.RequestCount);
        Assert.Equal(payload.Length / 2, handler.SecondRangeStart);
        Assert.Equal(payload, await File.ReadAllBytesAsync(Path.Combine(directory.Path, "retry.bin")));
    }

    [Fact]
    public async Task FailsBeforeWritingWhenDiskSpaceIsInsufficient()
    {
        byte[] payload = CreatePayload(4096, 157);
        using TemporaryDirectory directory = new();
        using HttpClient client = new(new RangeHandler(payload));
        ApplicationState state = new();
        using DownloadManager manager = CreateManager(
            client,
            state,
            new InMemoryHistoryStore(),
            new FixedDiskSpaceProvider(1));

        string id = await manager.AddAsync(new DownloadRequest(
            new Uri("https://example.test/no-space.bin"),
            directory.Path,
            "no-space.bin"));

        DownloadSnapshot failed = await WaitForStateAsync(state, id, DownloadState.Failed);

        Assert.Contains("Not enough free space", failed.ErrorMessage, StringComparison.Ordinal);
        Assert.False(File.Exists(Path.Combine(directory.Path, "no-space.bin.part")));
    }

    [Fact]
    public async Task RecoversInterruptedFinalizationOnStartup()
    {
        byte[] payload = CreatePayload(2048, 149);
        using TemporaryDirectory directory = new();
        string destination = Path.Combine(directory.Path, "recover.bin");
        await File.WriteAllBytesAsync($"{destination}.part", payload);
        await File.WriteAllTextAsync($"{destination}.finalizing", payload.Length.ToString(System.Globalization.CultureInfo.InvariantCulture));

        PersistedDownload persisted = new(
            "recover",
            new Uri("https://example.test/recover.bin"),
            destination,
            payload.Length,
            payload.Length,
            DownloadState.Finalizing,
            DateTimeOffset.UtcNow);
        ApplicationState state = new();
        using HttpClient client = new(new RangeHandler(payload));
        using DownloadManager manager = CreateManager(client, state, new InMemoryHistoryStore([persisted]));

        await manager.InitializeAsync();

        DownloadSnapshot completed = state.Current.Downloads.Single(item => item.Id == "recover");
        Assert.Equal(DownloadState.Completed, completed.State);
        Assert.Equal(payload, await File.ReadAllBytesAsync(destination));
        Assert.False(File.Exists($"{destination}.finalizing"));
    }

    [Fact]
    public async Task StartsNonDefaultQueueOnlyWhenActivated()
    {
        byte[] payload = [7, 8, 9, 10];
        using TemporaryDirectory directory = new();
        using HttpClient client = new(new RangeHandler(payload));
        ApplicationState state = new();
        using DownloadManager manager = CreateManager(client, state, new InMemoryHistoryStore());

        string id = await manager.AddAsync(new DownloadRequest(
            new Uri("https://example.test/queued.bin"),
            directory.Path,
            "queued.bin",
            QueueId: "night"));

        Assert.Equal(DownloadState.Queued, state.Current.Downloads.Single(item => item.Id == id).State);
        Assert.Equal("night", state.Current.Downloads.Single(item => item.Id == id).QueueId);

        await manager.StartQueueAsync("night");
        DownloadSnapshot completed = await WaitForStateAsync(state, id, DownloadState.Completed);

        Assert.Equal("night", completed.QueueId);
        Assert.Contains("night", manager.QueueRuntime.ActiveQueueIds);
    }

    [Fact]
    public async Task AppliesRequestMetadataAndRenamesCollisions()
    {
        byte[] payload = [1, 2, 3, 4];
        using TemporaryDirectory directory = new();
        await File.WriteAllTextAsync(Path.Combine(directory.Path, "payload.bin"), "existing");
        RangeHandler handler = new(payload);
        using HttpClient client = new(handler);
        ApplicationState state = new();
        using DownloadManager manager = CreateManager(client, state, new InMemoryHistoryStore());

        string id = await manager.AddAsync(new DownloadRequest(
            new Uri("https://example.test/payload.bin"),
            directory.Path,
            "payload.bin",
            new Dictionary<string, string> { ["X-Test"] = "value" },
            "user",
            "password",
            "session=abc",
            "https://example.test/page",
            "XDM-Test"));

        DownloadSnapshot completed = await WaitForStateAsync(state, id, DownloadState.Completed);

        Assert.Equal("payload (1).bin", completed.FileName);
        Assert.Equal("value", handler.LastTestHeader);
        Assert.Equal("Basic", handler.LastAuthorizationScheme);
        Assert.Equal("session=abc", handler.LastCookie);
    }

    private static DownloadManager CreateManager(
        HttpClient client,
        ApplicationState state,
        InMemoryHistoryStore history,
        IDiskSpaceProvider? diskSpaceProvider = null,
        DownloadRetryPolicy? retryPolicy = null)
        => new(
            client,
            state,
            history,
            new TestSettingsService(),
            NullLogger<DownloadManager>.Instance,
            diskSpaceProvider ?? new FixedDiskSpaceProvider(long.MaxValue),
            retryPolicy ?? new DownloadRetryPolicy(3, TimeSpan.FromMilliseconds(1), 0));

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

    private static byte[] CreatePayload(int length, int modulus)
        => Enumerable.Range(0, length).Select(value => (byte)(value % modulus)).ToArray();

    private sealed class RangeHandler(byte[] payload, string entityTag = "\"stable\"") : HttpMessageHandler
    {
        public long? LastRangeStart { get; private set; }

        public string? LastIfRangeEntityTag { get; private set; }

        public string? LastTestHeader { get; private set; }

        public string? LastAuthorizationScheme { get; private set; }

        public string? LastCookie { get; private set; }

        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            LastTestHeader = request.Headers.TryGetValues("X-Test", out IEnumerable<string>? testValues)
                ? testValues.Single()
                : null;
            LastAuthorizationScheme = request.Headers.Authorization?.Scheme;
            LastCookie = request.Headers.TryGetValues("Cookie", out IEnumerable<string>? cookieValues)
                ? cookieValues.Single()
                : null;
            LastRangeStart = request.Headers.Range?.Ranges.FirstOrDefault()?.From;
            LastIfRangeEntityTag = request.Headers.IfRange?.EntityTag?.ToString();
            int offset = checked((int)(LastRangeStart ?? 0));
            ByteArrayContent content = new(payload[offset..]);
            HttpResponseMessage response = new(
                offset > 0 ? HttpStatusCode.PartialContent : HttpStatusCode.OK)
            {
                Content = content
            };

            response.Headers.ETag = EntityTagHeaderValue.Parse(entityTag);
            response.Content.Headers.ContentLength = payload.Length - offset;
            if (offset > 0)
            {
                response.Content.Headers.ContentRange = new ContentRangeHeaderValue(
                    offset,
                    payload.Length - 1,
                    payload.Length);
            }

            return Task.FromResult(response);
        }
    }

    private sealed class IgnoreRangeHandler(byte[] payload) : HttpMessageHandler
    {
        public long? RequestedRangeStart { get; private set; }

        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            RequestedRangeStart = request.Headers.Range?.Ranges.FirstOrDefault()?.From;
            ByteArrayContent content = new(payload);
            content.Headers.ContentLength = payload.Length;
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK) { Content = content });
        }
    }

    private sealed class TruncatedThenRangeHandler(byte[] payload) : HttpMessageHandler
    {
        public int RequestCount { get; private set; }

        public long? SecondRangeStart { get; private set; }

        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            RequestCount++;
            long offset = request.Headers.Range?.Ranges.FirstOrDefault()?.From ?? 0;
            if (RequestCount == 2)
            {
                SecondRangeStart = offset;
            }

            int start = checked((int)offset);
            byte[] responsePayload = RequestCount == 1
                ? payload[..(payload.Length / 2)]
                : payload[start..];
            ByteArrayContent content = new(responsePayload);
            HttpResponseMessage response = new(
                offset > 0 ? HttpStatusCode.PartialContent : HttpStatusCode.OK)
            {
                Content = content
            };
            response.Headers.ETag = EntityTagHeaderValue.Parse("\"retry-v1\"");
            content.Headers.ContentLength = RequestCount == 1
                ? payload.Length
                : payload.Length - start;
            if (offset > 0)
            {
                content.Headers.ContentRange = new ContentRangeHeaderValue(start, payload.Length - 1, payload.Length);
            }

            return Task.FromResult(response);
        }
    }

    private sealed class FixedDiskSpaceProvider(long availableBytes) : IDiskSpaceProvider
    {
        public long? GetAvailableBytes(string path)
            => availableBytes;
    }

    private sealed class InMemoryHistoryStore : IDownloadHistoryStore
    {
        public InMemoryHistoryStore(IReadOnlyList<PersistedDownload>? downloads = null)
        {
            Downloads = downloads ?? [];
        }

        public IReadOnlyList<PersistedDownload> Downloads { get; private set; }

        public Task<IReadOnlyList<PersistedDownload>> LoadAsync(CancellationToken cancellationToken = default)
            => Task.FromResult(Downloads);

        public Task SaveAsync(
            IReadOnlyCollection<PersistedDownload> downloads,
            CancellationToken cancellationToken = default)
        {
            Downloads = downloads.ToArray();
            return Task.CompletedTask;
        }
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

    private sealed class TemporaryDirectory : IDisposable
    {
        public TemporaryDirectory()
        {
            Path = System.IO.Path.Combine(System.IO.Path.GetTempPath(), $"xdm-tests-{Guid.NewGuid():N}");
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
