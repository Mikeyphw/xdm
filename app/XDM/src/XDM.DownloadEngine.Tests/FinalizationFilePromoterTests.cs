using System.Security.Cryptography;
using XDM.DownloadEngine;

namespace XDM.DownloadEngine.Tests;

public sealed class FinalizationFilePromoterTests
{
    [Fact]
    public async Task FallsBackToDurableDestinationCopyWhenDirectMoveFails()
    {
        string directory = Path.Combine(Path.GetTempPath(), $"xdm-finalize-{Guid.NewGuid():N}");
        Directory.CreateDirectory(directory);
        try
        {
            byte[] payload = Enumerable.Range(0, 128 * 1024).Select(static value => (byte)(value % 251)).ToArray();
            string source = Path.Combine(directory, "source.part");
            string destination = Path.Combine(directory, "destination.bin");
            await File.WriteAllBytesAsync(source, payload, CancellationToken.None);
            FinalizationJournalStore journal = new();
            int moveCalls = 0;
            FinalizationFilePromoter promoter = new(
                journal,
                (from, to, overwrite) =>
                {
                    moveCalls++;
                    if (moveCalls == 1)
                    {
                        throw new IOException("Simulated cross-device move failure.");
                    }
                    File.Move(from, to, overwrite);
                });
            FinalizationMarker marker = new(
                FinalizationMarker.CurrentVersion,
                payload.Length,
                DownloadChecksumService.Sha256,
                Convert.ToHexString(SHA256.HashData(payload)),
                DateTimeOffset.UtcNow,
                Stage: FinalizationStage.Prepared,
                SourcePath: source,
                UpdatedAt: DateTimeOffset.UtcNow);

            FinalizationPromotionResult result = await promoter.PromoteAsync(source, destination, marker, CancellationToken.None);

            Assert.True(result.UsedCrossFileSystemFallback);
            Assert.Equal(payload, await File.ReadAllBytesAsync(destination, CancellationToken.None));
            Assert.False(File.Exists(source));
            Assert.False(File.Exists(TransferArtifactPaths.GetFinalizationStagingPath(destination)));
            FinalizationMarker? saved = await journal.LoadAsync(destination, CancellationToken.None);
            Assert.NotNull(saved);
            Assert.Equal(FinalizationStage.DestinationCommitted, saved!.Stage);
        }
        finally
        {
            Directory.Delete(directory, recursive: true);
        }
    }

    [Fact]
    public async Task RefusesLateDestinationWhenOverwriteWasNotAdmitted()
    {
        string directory = Path.Combine(Path.GetTempPath(), $"xdm-finalize-{Guid.NewGuid():N}");
        Directory.CreateDirectory(directory);
        try
        {
            byte[] sourcePayload = [1, 2, 3, 4];
            byte[] existingPayload = [9, 8, 7];
            string source = Path.Combine(directory, "source.part");
            string destination = Path.Combine(directory, "destination.bin");
            await File.WriteAllBytesAsync(source, sourcePayload);
            await File.WriteAllBytesAsync(destination, existingPayload);
            FinalizationJournalStore journal = new();
            FinalizationFilePromoter promoter = new(journal);
            FinalizationMarker marker = new(
                FinalizationMarker.CurrentVersion,
                sourcePayload.Length,
                ChecksumAlgorithm: null,
                Checksum: null,
                CreatedAt: DateTimeOffset.UtcNow,
                Stage: FinalizationStage.Prepared,
                SourcePath: source,
                UpdatedAt: DateTimeOffset.UtcNow,
                AllowOverwrite: false);

            await Assert.ThrowsAsync<IOException>(() =>
                promoter.PromoteAsync(source, destination, marker, CancellationToken.None));

            Assert.Equal(existingPayload, await File.ReadAllBytesAsync(destination));
            Assert.Equal(sourcePayload, await File.ReadAllBytesAsync(source));
        }
        finally
        {
            Directory.Delete(directory, recursive: true);
        }
    }

    [Fact]
    public async Task FinalizationValidationEnforcesBothExpectedChecksums()
    {
        string directory = Path.Combine(Path.GetTempPath(), $"xdm-finalize-{Guid.NewGuid():N}");
        Directory.CreateDirectory(directory);
        try
        {
            byte[] payload = [1, 3, 5, 7, 9];
            string candidate = Path.Combine(directory, "candidate.bin");
            await File.WriteAllBytesAsync(candidate, payload);
            FinalizationMarker marker = new(
                FinalizationMarker.CurrentVersion,
                payload.Length,
                ChecksumAlgorithm: null,
                Checksum: null,
                CreatedAt: DateTimeOffset.UtcNow,
                ExpectedSha256: Convert.ToHexString(SHA256.HashData(payload)),
                ExpectedSha512: new string('0', 128));

            DownloadIntegrityException error = await Assert.ThrowsAsync<DownloadIntegrityException>(() =>
                FinalizationFilePromoter.ValidateCandidateAsync(candidate, marker, CancellationToken.None));

            Assert.Contains("SHA-512", error.Message, StringComparison.OrdinalIgnoreCase);
        }
        finally
        {
            Directory.Delete(directory, recursive: true);
        }
    }

    [Fact]
    public async Task CorruptFinalizationJournalIsQuarantinedInsteadOfThrowing()
    {
        string directory = Path.Combine(Path.GetTempPath(), $"xdm-finalize-{Guid.NewGuid():N}");
        Directory.CreateDirectory(directory);
        try
        {
            string destination = Path.Combine(directory, "corrupt-journal.bin");
            string markerPath = TransferArtifactPaths.GetFinalizationMarkerPath(destination);
            await File.WriteAllTextAsync(markerPath, "{ definitely not a journal }");
            FinalizationJournalStore journal = new();

            FinalizationMarker? marker = await journal.LoadAsync(destination, CancellationToken.None);

            Assert.Null(marker);
            Assert.False(File.Exists(markerPath));
            Assert.NotEmpty(Directory.GetFiles(directory, "corrupt-journal.bin.xdm.finalizing.corrupt-*"));
        }
        finally
        {
            Directory.Delete(directory, recursive: true);
        }
    }

    [Fact]
    public async Task LoadsLegacyLengthMarkerAsPreparedJournal()
    {
        string directory = Path.Combine(Path.GetTempPath(), $"xdm-finalize-{Guid.NewGuid():N}");
        Directory.CreateDirectory(directory);
        try
        {
            string destination = Path.Combine(directory, "legacy.bin");
            await File.WriteAllTextAsync(
                TransferArtifactPaths.GetFinalizationMarkerPath(destination),
                "4096",
                CancellationToken.None);
            FinalizationJournalStore journal = new();

            FinalizationMarker? marker = await journal.LoadAsync(destination, CancellationToken.None);

            Assert.NotNull(marker);
            Assert.Equal(FinalizationMarker.CurrentVersion, marker!.Version);
            Assert.Equal(4096, marker.ExpectedLength);
            Assert.Equal(FinalizationStage.Prepared, marker.Stage);
        }
        finally
        {
            Directory.Delete(directory, recursive: true);
        }
    }
}
