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
