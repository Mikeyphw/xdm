using XDM.Core.Downloads;
using XDM.Core.Persistence;
using XDM.Persistence;

namespace XDM.DownloadEngine.Tests;

public sealed class JsonDownloadHistoryStoreTests
{
    [Fact]
    public async Task RoundTripsHistoryAtomically()
    {
        string directory = Path.Combine(Path.GetTempPath(), $"xdm-history-{Guid.NewGuid():N}");
        Directory.CreateDirectory(directory);

        try
        {
            string path = Path.Combine(directory, "downloads.json");
            JsonDownloadHistoryStore store = new(path);
            PersistedDownload expected = new(
                "download-1",
                new Uri("https://example.test/file.zip"),
                Path.Combine(directory, "file.zip"),
                128,
                512,
                DownloadState.Paused,
                DateTimeOffset.UtcNow,
                QueueId: "night",
                CategoryId: "archives",
                QueueOrder: 3);

            await store.SaveAsync([expected]);
            IReadOnlyList<PersistedDownload> loaded = await store.LoadAsync();

            PersistedDownload actual = Assert.Single(loaded);
            Assert.Equal(expected.Id, actual.Id);
            Assert.Equal(expected.Source, actual.Source);
            Assert.Equal(expected.DownloadedBytes, actual.DownloadedBytes);
            Assert.Equal("night", actual.QueueId);
            Assert.Equal("archives", actual.CategoryId);
            Assert.Equal(3, actual.QueueOrder);
            Assert.False(File.Exists($"{path}.tmp"));
        }
        finally
        {
            Directory.Delete(directory, recursive: true);
        }
    }
}
