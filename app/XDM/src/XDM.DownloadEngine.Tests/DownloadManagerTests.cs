using System.Net;
using System.Net.Http.Headers;
using Microsoft.Extensions.Logging.Abstractions;
using XDM.Core.Downloads;
using XDM.Core.Persistence;
using XDM.Core.State;

namespace XDM.DownloadEngine.Tests;

public sealed class DownloadManagerTests
{
    [Fact]
    public async Task CompletesDownloadAndPublishesState()
    {
        byte[] payload = Enumerable.Range(0, 4096).Select(static value => (byte)(value % 251)).ToArray();
        using TemporaryDirectory directory = new();
        using HttpClient client = new(new RangeHandler(payload));
        ApplicationState state = new();
        InMemoryHistoryStore history = new();
        using DownloadManager manager = new(client, state, history, NullLogger<DownloadManager>.Instance);

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
        byte[] payload = Enumerable.Range(0, 8192).Select(static value => (byte)(value % 239)).ToArray();
        const int partialLength = 2048;
        using TemporaryDirectory directory = new();
        string destination = Path.Combine(directory.Path, "resume.bin");
        await File.WriteAllBytesAsync($"{destination}.part", payload[..partialLength]);

        RangeHandler handler = new(payload);
        using HttpClient client = new(handler);
        ApplicationState state = new();
        using DownloadManager manager = new(client, state, new InMemoryHistoryStore(), NullLogger<DownloadManager>.Instance);

        string id = await manager.AddAsync(new DownloadRequest(
            new Uri("https://example.test/resume.bin"),
            directory.Path,
            "resume.bin"));

        await WaitForStateAsync(state, id, DownloadState.Completed);

        Assert.Equal(partialLength, handler.LastRangeStart);
        Assert.Equal(payload, await File.ReadAllBytesAsync(destination));
    }

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

            if (snapshot?.State == DownloadState.Failed)
            {
                throw new InvalidOperationException(snapshot.ErrorMessage);
            }

            await Task.Delay(20, timeout.Token);
        }

        throw new TimeoutException($"Download did not reach {expected}.");
    }

    private sealed class RangeHandler(byte[] payload) : HttpMessageHandler
    {
        public long? LastRangeStart { get; private set; }

        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            LastRangeStart = request.Headers.Range?.Ranges.FirstOrDefault()?.From;
            int offset = checked((int)(LastRangeStart ?? 0));
            ByteArrayContent content = new(payload[offset..]);
            HttpResponseMessage response = new(
                offset > 0 ? HttpStatusCode.PartialContent : HttpStatusCode.OK)
            {
                Content = content
            };

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

    private sealed class InMemoryHistoryStore : IDownloadHistoryStore
    {
        public IReadOnlyList<PersistedDownload> Downloads { get; private set; } = [];

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
