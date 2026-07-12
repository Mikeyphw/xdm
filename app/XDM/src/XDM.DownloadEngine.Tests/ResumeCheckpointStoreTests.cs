namespace XDM.DownloadEngine.Tests;

public sealed class ResumeCheckpointStoreTests
{
    [Fact]
    public async Task SavesAndLoadsCheckpointAtomically()
    {
        using TemporaryDirectory directory = new();
        string destination = Path.Combine(directory.Path, "payload.bin");
        ResumeCheckpointStore store = new();
        ResumeCheckpoint checkpoint = new(
            ResumeCheckpoint.CurrentVersion,
            "download-1",
            new Uri("https://example.test/payload.bin"),
            destination,
            4096,
            8192,
            "\"v1\"",
            DateTimeOffset.Parse("2026-07-12T12:00:00Z", System.Globalization.CultureInfo.InvariantCulture),
            4,
            DateTimeOffset.UtcNow,
            DownloadChecksumService.Sha256,
            new string('A', 64),
            [new Uri("https://mirror.example.test/payload.bin")],
            new Dictionary<int, long> { [0] = 2048, [1] = 2048 });

        await store.SaveAsync(checkpoint);
        ResumeCheckpoint? loaded = await store.LoadAsync(destination);

        Assert.NotNull(loaded);
        Assert.Equal(checkpoint.DownloadId, loaded.DownloadId);
        Assert.Equal(checkpoint.Source, loaded.Source);
        Assert.Equal(checkpoint.DownloadedBytes, loaded.DownloadedBytes);
        Assert.Equal(checkpoint.ExpectedChecksum, loaded.ExpectedChecksum);
        Assert.Equal(
            (checkpoint.Mirrors ?? Array.Empty<Uri>()).ToArray(),
            (loaded.Mirrors ?? Array.Empty<Uri>()).ToArray());
        Assert.Equal(
            (checkpoint.SegmentLengths ?? new Dictionary<int, long>()).OrderBy(static pair => pair.Key).ToArray(),
            (loaded.SegmentLengths ?? new Dictionary<int, long>()).OrderBy(static pair => pair.Key).ToArray());
        Assert.False(File.Exists($"{TransferArtifactPaths.GetCheckpointPath(destination)}.tmp"));
    }

    [Fact]
    public async Task QuarantinesMalformedCheckpoint()
    {
        using TemporaryDirectory directory = new();
        string destination = Path.Combine(directory.Path, "payload.bin");
        string checkpointPath = TransferArtifactPaths.GetCheckpointPath(destination);
        await File.WriteAllTextAsync(checkpointPath, "{not-json");
        ResumeCheckpointStore store = new();

        ResumeCheckpoint? loaded = await store.LoadAsync(destination);

        Assert.Null(loaded);
        Assert.False(File.Exists(checkpointPath));
        Assert.Single(Directory.EnumerateFiles(directory.Path, "*.corrupt-*"));
    }


    [Fact]
    public async Task QuarantinesOversizedCheckpoint()
    {
        using TemporaryDirectory directory = new();
        string destination = Path.Combine(directory.Path, "payload.bin");
        string checkpointPath = TransferArtifactPaths.GetCheckpointPath(destination);
        await using (FileStream stream = new(checkpointPath, FileMode.CreateNew, FileAccess.Write, FileShare.None))
        {
            stream.SetLength((2L * 1024 * 1024) + 1);
        }
        ResumeCheckpointStore store = new();

        ResumeCheckpoint? loaded = await store.LoadAsync(destination);

        Assert.Null(loaded);
        Assert.False(File.Exists(checkpointPath));
        Assert.Single(Directory.EnumerateFiles(directory.Path, "*.corrupt-*"));
    }

    private sealed class TemporaryDirectory : IDisposable
    {
        public TemporaryDirectory()
        {
            Path = System.IO.Path.Combine(System.IO.Path.GetTempPath(), $"xdm-checkpoint-tests-{Guid.NewGuid():N}");
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
