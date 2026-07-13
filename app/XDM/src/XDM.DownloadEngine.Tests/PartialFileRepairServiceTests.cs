using System.Net;
using System.Net.Http.Headers;

namespace XDM.DownloadEngine.Tests;

public sealed class PartialFileRepairServiceTests
{
    [Fact]
    public async Task SavedBlockHashesAvoidRedownloadingKnownGoodRanges()
    {
        const int blockSize = 64 * 1024;
        byte[] payload = Enumerable.Range(0, blockSize * 3)
            .Select(static value => (byte)(value % 251))
            .ToArray();
        using TemporaryDirectory directory = new();
        string localPath = Path.Combine(directory.Path, "partial.bin");
        await File.WriteAllBytesAsync(localPath, payload, CancellationToken.None);
        RangeHandler handler = new(payload);
        using HttpClient client = new(handler);
        PartialFileRepairService service = new(client);
        PartialFileRepairRequest request = new(
            new Uri("https://example.test/partial.bin"),
            localPath,
            payload.Length,
            "\"stable\"",
            null,
            BlockSizeBytes: blockSize);

        PartialFileRepairResult baseline = await service.RepairAsync(
            request,
            cancellationToken: CancellationToken.None);
        Assert.Empty(baseline.RepairedRanges);
        Assert.Equal(payload.Length, baseline.BytesDownloaded);

        byte[] corrupted = payload.ToArray();
        corrupted[blockSize + 9] ^= 0x55;
        await File.WriteAllBytesAsync(localPath, corrupted, CancellationToken.None);
        PartialFileRepairResult repaired = await service.RepairAsync(
            request,
            cancellationToken: CancellationToken.None);

        Assert.True(repaired.UsedSavedBlockHashes);
        Assert.Equal(blockSize, repaired.BytesDownloaded);
        Assert.Equal(blockSize, repaired.BytesRepaired);
        RepairedByteRange range = Assert.Single(repaired.RepairedRanges);
        Assert.Equal(blockSize, range.Start);
        Assert.Equal((2L * blockSize) - 1, range.End);
        Assert.Equal(payload, await File.ReadAllBytesAsync(localPath, CancellationToken.None));
    }

    private sealed class RangeHandler(byte[] payload) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            Assert.NotNull(request.Headers.Range);
            RangeItemHeaderValue range = Assert.Single(request.Headers.Range.Ranges);
            int start = checked((int)(range.From ?? 0));
            int end = checked((int)(range.To ?? payload.Length - 1));
            byte[] slice = payload[start..(end + 1)];
            ByteArrayContent content = new(slice);
            content.Headers.ContentLength = slice.Length;
            content.Headers.ContentRange = new ContentRangeHeaderValue(start, end, payload.Length);
            HttpResponseMessage response = new(HttpStatusCode.PartialContent)
            {
                Content = content
            };
            response.Headers.ETag = EntityTagHeaderValue.Parse("\"stable\"");
            return Task.FromResult(response);
        }
    }

    private sealed class TemporaryDirectory : IDisposable
    {
        public TemporaryDirectory()
        {
            Path = System.IO.Path.Combine(
                System.IO.Path.GetTempPath(),
                $"xdm-repair-tests-{Guid.NewGuid():N}");
            Directory.CreateDirectory(Path);
        }

        public string Path { get; }

        public void Dispose()
        {
            try
            {
                Directory.Delete(Path, recursive: true);
            }
            catch (IOException)
            {
            }
            catch (UnauthorizedAccessException)
            {
            }
        }
    }
}
