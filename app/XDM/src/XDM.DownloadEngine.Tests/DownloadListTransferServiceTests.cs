using System.Text.Json;
using XDM.Core.Downloads;
using XDM.Core.Persistence;
using XDM.Persistence;

namespace XDM.DownloadEngine.Tests;

public sealed class DownloadListTransferServiceTests
{
    [Fact]
    public async Task ExportRoundTripsSafeHistoryMetadataWithoutSecrets()
    {
        using TemporaryDirectory directory = new();
        string path = Path.Combine(directory.Path, "downloads.json");
        DownloadSnapshot[] snapshots =
        [
            new DownloadSnapshot(
                "one",
                "file.bin",
                new Uri("https://example.test/file.bin"),
                Path.Combine(directory.Path, "file.bin"),
                100,
                100,
                0,
                DownloadState.Completed,
                DateTimeOffset.UtcNow,
                QueueId: "default",
                CategoryId: "general",
                ConnectionCount: 8,
                Priority: DownloadPriority.High,
                SourcePage: new Uri("https://example.test/page"))
        ];
        DownloadListTransferService service = new();

        await service.ExportAsync(path, snapshots);
        DownloadListImportResult result = await service.ImportAsync(path);

        DownloadListEntry entry = Assert.Single(result.Downloads);
        Assert.Equal(snapshots[0].Source, entry.Source);
        Assert.Equal("file.bin", entry.FileName);
        Assert.Equal(8, entry.ConnectionCount);
        Assert.Equal(DownloadPriority.High, entry.Priority);
        Assert.Equal(snapshots[0].SourcePage, entry.SourcePage);
        string json = await File.ReadAllTextAsync(path);
        Assert.DoesNotContain("password", json, StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain("cookie", json, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public async Task ImportsPlainUrlListsAndIgnoresUnsafeSchemes()
    {
        using TemporaryDirectory directory = new();
        string path = Path.Combine(directory.Path, "urls.txt");
        await File.WriteAllTextAsync(
            path,
            "# export\nhttps://example.test/a\nftp://example.test/b\nnot-a-url\nhttp://example.test/c\n");
        DownloadListTransferService service = new();

        DownloadListImportResult result = await service.ImportAsync(path);

        Assert.Equal(2, result.Downloads.Count);
        Assert.Equal(2, result.IgnoredEntries);
        Assert.All(
            result.Downloads,
            static entry => Assert.True(entry.Source.Scheme is "http" or "https"));
    }

    [Fact]
    public async Task RejectsUnknownJsonSchema()
    {
        using TemporaryDirectory directory = new();
        string path = Path.Combine(directory.Path, "downloads.json");
        await File.WriteAllTextAsync(path, JsonSerializer.Serialize(new { schemaVersion = 99, downloads = Array.Empty<object>() }));
        DownloadListTransferService service = new();

        await Assert.ThrowsAsync<InvalidDataException>(() => service.ImportAsync(path));
    }

    private sealed class TemporaryDirectory : IDisposable
    {
        public TemporaryDirectory()
        {
            Path = System.IO.Path.Combine(System.IO.Path.GetTempPath(), $"xdm-list-tests-{Guid.NewGuid():N}");
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
