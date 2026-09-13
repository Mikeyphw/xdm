namespace XDM.DownloadEngine.Tests;

public sealed class DownloadChecksumWorkflowStoreTests
{
    [Fact]
    public async Task RoundTripsDualChecksumsAndVerificationResult()
    {
        using TemporaryDirectory directory = new();
        string destination = Path.Combine(directory.Path, "archive.bin");
        DownloadChecksumWorkflowStore store = new();
        DownloadChecksumWorkflowState expected = new(
            DownloadChecksumWorkflowState.CurrentVersion,
            destination,
            new string('A', 64),
            new string('B', 128),
            new string('C', 64),
            new string('D', 128),
            DateTimeOffset.Parse("2026-07-13T12:00:00Z", System.Globalization.CultureInfo.InvariantCulture),
            true,
            false,
            4096,
            4096);

        await store.SaveAsync(expected, CancellationToken.None);
        DownloadChecksumWorkflowState actual = await store.LoadAsync(destination, CancellationToken.None);

        Assert.Equal(expected, actual);
    }

    [Fact]
    public async Task CorruptSidecarIsQuarantinedWithoutAbortingLoad()
    {
        using TemporaryDirectory directory = new();
        string destination = Path.Combine(directory.Path, "corrupt.bin");
        string sidecar = TransferArtifactPaths.GetChecksumStatePath(destination);
        await File.WriteAllTextAsync(sidecar, "{not-json");
        DownloadChecksumWorkflowStore store = new();

        DownloadChecksumWorkflowState state = await store.LoadAsync(destination, "download-1");

        Assert.Equal(DownloadChecksumWorkflowState.CurrentVersion, state.Version);
        Assert.Equal("download-1", state.OwnerDownloadId);
        Assert.Null(state.ExpectedSha256);
        Assert.False(File.Exists(sidecar));
        Assert.NotEmpty(Directory.GetFiles(directory.Path, "corrupt.bin.xdm.checksums.json.corrupt-*"));
    }

    [Fact]
    public async Task SidecarOwnedByAnotherDownloadIsRejectedAndQuarantined()
    {
        using TemporaryDirectory directory = new();
        string destination = Path.Combine(directory.Path, "owned.bin");
        DownloadChecksumWorkflowStore store = new();
        await store.SaveAsync(new DownloadChecksumWorkflowState(
            DownloadChecksumWorkflowState.CurrentVersion,
            destination,
            new string('A', 64),
            null,
            null,
            null,
            null,
            null,
            false,
            OwnerDownloadId: "owner-a"));

        DownloadChecksumWorkflowState state = await store.LoadAsync(destination, "owner-b");

        Assert.Equal("owner-b", state.OwnerDownloadId);
        Assert.Null(state.ExpectedSha256);
        Assert.NotEmpty(Directory.GetFiles(directory.Path, "owned.bin.xdm.checksums.json.foreign-owner-*"));
    }

    private sealed class TemporaryDirectory : IDisposable
    {
        public TemporaryDirectory()
        {
            Path = System.IO.Path.Combine(
                System.IO.Path.GetTempPath(),
                $"xdm-checksum-state-{Guid.NewGuid():N}");
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
