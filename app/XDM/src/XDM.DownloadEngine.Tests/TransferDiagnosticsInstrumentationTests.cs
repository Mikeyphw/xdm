using System.Collections.Concurrent;
using System.Net;
using System.Net.Http.Headers;
using XDM.Core.Diagnostics;

namespace XDM.DownloadEngine.Tests;

public sealed class TransferDiagnosticsInstrumentationTests
{
    [Fact]
    public async Task SegmentedExecutorEmitsHttpDiskResumeAndRetrySafeEvents()
    {
        byte[] payload = Enumerable.Range(0, 128 * 1024).Select(static value => (byte)(value % 251)).ToArray();
        using HttpClient client = new(new RangeHandler(payload));
        RecordingSink sink = new();
        SegmentedDownloadExecutor executor = new(
            client,
            new UnlimitedDiskSpaceProvider(),
            new DownloadRetryPolicy(2, TimeSpan.FromMilliseconds(1), jitterFraction: 0),
            new SegmentedDownloadOptions(2, 4, 1),
            sink);
        string directory = Path.Combine(Path.GetTempPath(), $"xdm-transfer-diagnostics-{Guid.NewGuid():N}");
        Directory.CreateDirectory(directory);
        string destination = Path.Combine(directory, "fixture.bin");
        try
        {
            SegmentedDownloadResult? result = await executor.TryDownloadAsync(
                new SegmentedDownloadContext(
                    "download-1",
                    new Uri("https://example.test/fixture.bin"),
                    destination,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    2,
                    0),
                static (_, _, _) => ValueTask.CompletedTask,
                CancellationToken.None);

            Assert.NotNull(result);
            Assert.Contains(sink.Events, static item => item.Stage == TransferDiagnosticStage.Http);
            Assert.Contains(sink.Events, static item => item.Stage == TransferDiagnosticStage.Disk);
            Assert.Contains(sink.Events, static item => item.Stage == TransferDiagnosticStage.Resume);
            Assert.Contains(
                sink.Events,
                static item => item.Code == "XDM-TRANSFER-SEGMENT-COMPLETED"
                    && item.Context.ContainsKey("segmentIndex"));
            Assert.Contains(
                sink.Events,
                static item => item.Code == "XDM-TRANSFER-SEGMENT-PROBE-RESPONSE"
                    && item.Context.TryGetValue("header.ETag", out string? value)
                    && value == "\"stable\"");
            Assert.All(sink.Events, static item => Assert.Equal("download-1", item.DownloadId));
        }
        finally
        {
            Directory.Delete(directory, recursive: true);
        }
    }

    private sealed class RecordingSink : ITransferDiagnosticSink
    {
        private readonly ConcurrentQueue<TransferDiagnosticEvent> _events = new();

        public IReadOnlyCollection<TransferDiagnosticEvent> Events => _events.ToArray();

        public void Record(
            string downloadId,
            TransferDiagnosticStage stage,
            TransferDiagnosticSeverity severity,
            string code,
            string message,
            IReadOnlyDictionary<string, string?>? context = null)
            => _events.Enqueue(new TransferDiagnosticEvent(
                DateTimeOffset.UtcNow,
                downloadId,
                stage,
                severity,
                code,
                message,
                context ?? new Dictionary<string, string?>(StringComparer.Ordinal)));
    }

    private sealed class UnlimitedDiskSpaceProvider : IDiskSpaceProvider
    {
        public long? GetAvailableBytes(string path) => long.MaxValue;
    }

    private sealed class RangeHandler(byte[] payload) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            RangeItemHeaderValue? requested = request.Headers.Range?.Ranges.SingleOrDefault();
            long start = requested?.From ?? 0;
            long end = requested?.To ?? payload.Length - 1;
            int length = checked((int)(end - start + 1));
            ByteArrayContent content = new(payload.AsSpan(checked((int)start), length).ToArray());
            content.Headers.ContentRange = new ContentRangeHeaderValue(start, end, payload.Length);
            HttpResponseMessage response = new(HttpStatusCode.PartialContent)
            {
                Content = content
            };
            response.Headers.ETag = new EntityTagHeaderValue("\"stable\"");
            return Task.FromResult(response);
        }
    }
}
