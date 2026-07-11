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
                QueueOrder: 3,
                EntityTag: "\"version-1\"",
                LastModified: DateTimeOffset.Parse("2026-07-11T12:00:00Z", System.Globalization.CultureInfo.InvariantCulture),
                Method: "POST");

            await store.SaveAsync([expected]);
            IReadOnlyList<PersistedDownload> loaded = await store.LoadAsync();

            PersistedDownload actual = Assert.Single(loaded);
            Assert.Equal(expected.Id, actual.Id);
            Assert.Equal(expected.Source, actual.Source);
            Assert.Equal(expected.DownloadedBytes, actual.DownloadedBytes);
            Assert.Equal("night", actual.QueueId);
            Assert.Equal("archives", actual.CategoryId);
            Assert.Equal(3, actual.QueueOrder);
            Assert.Equal("\"version-1\"", actual.EntityTag);
            Assert.Equal(expected.LastModified, actual.LastModified);
            Assert.Equal("POST", actual.Method);
            Assert.False(File.Exists($"{path}.tmp"));
        }
        finally
        {
            Directory.Delete(directory, recursive: true);
        }
    }

    [Fact]
    public async Task RecoversLastKnownGoodBackupWhenPrimaryIsCorrupt()
    {
        string directory = Path.Combine(Path.GetTempPath(), $"xdm-history-{Guid.NewGuid():N}");
        Directory.CreateDirectory(directory);

        try
        {
            string path = Path.Combine(directory, "downloads.json");
            JsonDownloadHistoryStore store = new(path);
            PersistedDownload first = new(
                "first",
                new Uri("https://example.test/first.bin"),
                Path.Combine(directory, "first.bin"),
                10,
                100,
                DownloadState.Paused,
                DateTimeOffset.UtcNow);
            PersistedDownload second = first with { Id = "second" };

            await store.SaveAsync([first]);
            await store.SaveAsync([second]);
            await File.WriteAllTextAsync(path, "{broken");

            IReadOnlyList<PersistedDownload> recovered = await store.LoadAsync();

            Assert.Equal("first", Assert.Single(recovered).Id);
            Assert.Contains(Directory.EnumerateFiles(directory), file => file.Contains(".corrupt-", StringComparison.Ordinal));
        }
        finally
        {
            Directory.Delete(directory, recursive: true);
        }
    }
}
