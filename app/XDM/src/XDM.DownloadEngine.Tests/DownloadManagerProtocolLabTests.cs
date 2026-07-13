using Microsoft.Extensions.Logging.Abstractions;
using XDM.Core.Downloads;
using XDM.Core.Persistence;
using XDM.Core.Settings;
using XDM.Core.State;
using XDM.DownloadEngine.Tests.FaultLab;

namespace XDM.DownloadEngine.Tests;

public sealed class DownloadManagerProtocolLabTests
{
    [Fact]
    public async Task InterruptedRealHttpResponseRetriesAndResumesFromCheckpoint()
    {
        CancellationToken cancellationToken = CancellationToken.None;
        await using ProtocolLabScenarioServer server = new();
        using TemporaryDirectory directory = new();
        using HttpClient client = CreateClient();
        ApplicationState state = new();
        using DownloadManager manager = CreateManager(client, state);

        string id = await manager.AddAsync(
            new DownloadRequest(
                server.GetUri("interrupt"),
                directory.Path,
                "interrupted.bin",
                ConnectionCount: 1),
            cancellationToken);
        await WaitForStateAsync(state, id, DownloadState.Completed, cancellationToken);

        Assert.Equal(2, server.RequestCount);
        Assert.Equal(server.Payload, await File.ReadAllBytesAsync(
            Path.Combine(directory.Path, "interrupted.bin"),
            cancellationToken));
        Assert.Equal((long)server.Payload.Length / 2, server.Requests[1].RangeStart);
        Assert.Equal("\"protocol-lab-v1\"", server.Requests[1].IfRange);
    }

    [Fact]
    public async Task ChangedEntityTagOnSatisfiedRangeCannotFinalizeThePartial()
    {
        CancellationToken cancellationToken = CancellationToken.None;
        byte[] payload = CreatePayload(8192);
        using TemporaryDirectory directory = new();
        string destination = Path.Combine(directory.Path, "changed-416.bin");
        string partialPath = TransferArtifactPaths.GetPartialPath(destination);
        await File.WriteAllBytesAsync(partialPath, payload, cancellationToken);
        await using DeterministicHttpFaultServer server = new((request, _) =>
        {
            Assert.Equal((long)payload.Length, request.RangeStart);
            Assert.Equal("\"old-v1\"", request.IfRange);
            return FaultResponse.RangeNotSatisfiable(payload.Length, "\"new-v2\"");
        });
        Uri source = server.GetUri("changed-416.bin");
        PersistedDownload persisted = new(
            "changed-416",
            source,
            destination,
            payload.Length,
            payload.Length,
            DownloadState.Paused,
            DateTimeOffset.UtcNow,
            EntityTag: "\"old-v1\"");
        await SaveCheckpointAsync(persisted, "\"old-v1\"", cancellationToken);
        ApplicationState state = new();
        using HttpClient client = CreateClient();
        using DownloadManager manager = CreateManager(client, state, [persisted]);

        await manager.InitializeAsync(cancellationToken);
        await manager.ResumeAsync(persisted.Id, cancellationToken);
        DownloadSnapshot failed = await WaitForStateAsync(
            state,
            persisted.Id,
            DownloadState.Failed,
            cancellationToken);

        Assert.Contains("entity tag changed", failed.ErrorMessage, StringComparison.OrdinalIgnoreCase);
        Assert.True(File.Exists(partialPath));
        Assert.False(File.Exists(destination));
    }

    [Fact]
    public async Task ContradictoryContentRangeCannotAppendToThePartial()
    {
        CancellationToken cancellationToken = CancellationToken.None;
        await using ProtocolLabScenarioServer server = new();
        using TemporaryDirectory directory = new();
        const int partialLength = 1024;
        string destination = Path.Combine(directory.Path, "invalid-range.bin");
        string partialPath = TransferArtifactPaths.GetPartialPath(destination);
        await File.WriteAllBytesAsync(partialPath, server.Payload[..partialLength], cancellationToken);
        PersistedDownload persisted = new(
            "invalid-range",
            server.GetUri("range/invalid"),
            destination,
            partialLength,
            server.Payload.Length,
            DownloadState.Paused,
            DateTimeOffset.UtcNow,
            EntityTag: "\"protocol-lab-v1\"");
        await SaveCheckpointAsync(persisted, persisted.EntityTag, cancellationToken);
        ApplicationState state = new();
        using HttpClient client = CreateClient();
        using DownloadManager manager = CreateManager(client, state, [persisted]);

        await manager.InitializeAsync(cancellationToken);
        await manager.ResumeAsync(persisted.Id, cancellationToken);
        DownloadSnapshot failed = await WaitForStateAsync(
            state,
            persisted.Id,
            DownloadState.Failed,
            cancellationToken);

        Assert.Contains("invalid range", failed.ErrorMessage, StringComparison.OrdinalIgnoreCase);
        Assert.Equal((long)partialLength, new FileInfo(partialPath).Length);
        Assert.False(File.Exists(destination));
    }

    [Fact]
    public async Task IgnoredRangeRestartsWithoutDuplicatingTheExistingBytes()
    {
        CancellationToken cancellationToken = CancellationToken.None;
        await using ProtocolLabScenarioServer server = new();
        using TemporaryDirectory directory = new();
        const int partialLength = 2048;
        string destination = Path.Combine(directory.Path, "ignored-range.bin");
        string partialPath = TransferArtifactPaths.GetPartialPath(destination);
        await File.WriteAllBytesAsync(partialPath, server.Payload[..partialLength], cancellationToken);
        PersistedDownload persisted = new(
            "ignored-range",
            server.GetUri("range/ignored"),
            destination,
            partialLength,
            server.Payload.Length,
            DownloadState.Paused,
            DateTimeOffset.UtcNow);
        await SaveCheckpointAsync(persisted, null, cancellationToken);
        ApplicationState state = new();
        using HttpClient client = CreateClient();
        using DownloadManager manager = CreateManager(client, state, [persisted]);

        await manager.InitializeAsync(cancellationToken);
        await manager.ResumeAsync(persisted.Id, cancellationToken);
        await WaitForStateAsync(state, persisted.Id, DownloadState.Completed, cancellationToken);

        Assert.Equal(server.Payload, await File.ReadAllBytesAsync(destination, cancellationToken));
        Assert.Equal((long)partialLength, Assert.Single(server.Requests).RangeStart);
    }

    [Fact]
    public async Task RedirectChainCompletesThroughTheNormalEnginePipeline()
    {
        CancellationToken cancellationToken = CancellationToken.None;
        await using ProtocolLabScenarioServer server = new();
        using TemporaryDirectory directory = new();
        ApplicationState state = new();
        using HttpClient client = CreateClient();
        using DownloadManager manager = CreateManager(client, state);

        string id = await manager.AddAsync(
            new DownloadRequest(server.GetUri("redirect/2"), directory.Path, "redirected.bin"),
            cancellationToken);
        await WaitForStateAsync(state, id, DownloadState.Completed, cancellationToken);

        Assert.Equal(server.Payload, await File.ReadAllBytesAsync(
            Path.Combine(directory.Path, "redirected.bin"),
            cancellationToken));
        Assert.True(server.RequestCount >= 4);
    }

    [Fact]
    public async Task RateLimitedTransferRetriesAndCompletes()
    {
        CancellationToken cancellationToken = CancellationToken.None;
        await using ProtocolLabScenarioServer server = new();
        using TemporaryDirectory directory = new();
        ApplicationState state = new();
        using HttpClient client = CreateClient();
        using DownloadManager manager = CreateManager(client, state);

        string id = await manager.AddAsync(
            new DownloadRequest(server.GetUri("rate-limit"), directory.Path, "rate-limited.bin"),
            cancellationToken);
        await WaitForStateAsync(state, id, DownloadState.Completed, cancellationToken);

        Assert.Equal(2, server.RequestCount);
        Assert.Equal(server.Payload, await File.ReadAllBytesAsync(
            Path.Combine(directory.Path, "rate-limited.bin"),
            cancellationToken));
    }

    [Fact]
    public async Task ChunkedTransferCompletesWithoutKnownContentLength()
    {
        CancellationToken cancellationToken = CancellationToken.None;
        await using ProtocolLabScenarioServer server = new();
        using TemporaryDirectory directory = new();
        ApplicationState state = new();
        using HttpClient client = CreateClient();
        using DownloadManager manager = CreateManager(client, state);

        string id = await manager.AddAsync(
            new DownloadRequest(server.GetUri("chunked"), directory.Path, "chunked.bin"),
            cancellationToken);
        DownloadSnapshot completed = await WaitForStateAsync(
            state,
            id,
            DownloadState.Completed,
            cancellationToken);

        Assert.Equal((long)server.Payload.Length, completed.DownloadedBytes);
        Assert.Equal(server.Payload, await File.ReadAllBytesAsync(
            Path.Combine(directory.Path, "chunked.bin"),
            cancellationToken));
    }

    private static DownloadManager CreateManager(
        HttpClient client,
        ApplicationState state,
        PersistedDownload[]? downloads = null)
        => new(
            client,
            state,
            new InMemoryHistoryStore(downloads),
            new TestSettingsService(),
            NullLogger<DownloadManager>.Instance,
            new FixedDiskSpaceProvider(long.MaxValue),
            new DownloadRetryPolicy(3, TimeSpan.FromMilliseconds(1), 0));

    private static async Task SaveCheckpointAsync(
        PersistedDownload download,
        string? entityTag,
        CancellationToken cancellationToken)
    {
        await new ResumeCheckpointStore().SaveAsync(
            new ResumeCheckpoint(
                ResumeCheckpoint.CurrentVersion,
                download.Id,
                download.Source,
                download.DestinationPath,
                download.DownloadedBytes,
                download.TotalBytes,
                entityTag,
                download.LastModified,
                1,
                DateTimeOffset.UtcNow),
            cancellationToken);
    }

    private static async Task<DownloadSnapshot> WaitForStateAsync(
        ApplicationState state,
        string id,
        DownloadState expected,
        CancellationToken cancellationToken)
    {
        using CancellationTokenSource timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(TimeSpan.FromSeconds(10));
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
        => Enumerable.Range(0, length).Select(static value => (byte)(value % 239)).ToArray();

    private static HttpClient CreateClient()
        => new() { Timeout = TimeSpan.FromSeconds(10) };

    private sealed class FixedDiskSpaceProvider(long availableBytes) : IDiskSpaceProvider
    {
        public long? GetAvailableBytes(string path)
            => availableBytes;
    }

    private sealed class InMemoryHistoryStore(PersistedDownload[]? downloads = null)
        : IDownloadHistoryStore
    {
        public IReadOnlyList<PersistedDownload> Downloads { get; private set; } = downloads ?? [];

        public Task<IReadOnlyList<PersistedDownload>> LoadAsync(CancellationToken cancellationToken = default)
        {
            cancellationToken.ThrowIfCancellationRequested();
            return Task.FromResult(Downloads);
        }

        public Task SaveAsync(
            IReadOnlyCollection<PersistedDownload> downloadsToSave,
            CancellationToken cancellationToken = default)
        {
            cancellationToken.ThrowIfCancellationRequested();
            Downloads = downloadsToSave.ToArray();
            return Task.CompletedTask;
        }
    }

    private sealed class TestSettingsService : ISettingsService
    {
        public ApplicationSettings Current { get; private set; } = ApplicationSettings.CreateDefault().Normalize();

        public event EventHandler<ApplicationSettings>? Changed;

        public Task InitializeAsync(CancellationToken cancellationToken = default)
        {
            cancellationToken.ThrowIfCancellationRequested();
            return Task.CompletedTask;
        }

        public Task UpdateAsync(
            ApplicationSettings settings,
            CancellationToken cancellationToken = default)
        {
            cancellationToken.ThrowIfCancellationRequested();
            Current = settings.Normalize();
            Changed?.Invoke(this, Current);
            return Task.CompletedTask;
        }
    }

    private sealed class TemporaryDirectory : IDisposable
    {
        public TemporaryDirectory()
        {
            Path = System.IO.Path.Combine(
                System.IO.Path.GetTempPath(),
                $"xdm-protocol-lab-{Guid.NewGuid():N}");
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
