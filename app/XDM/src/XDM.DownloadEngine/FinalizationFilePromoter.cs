namespace XDM.DownloadEngine;

public sealed class FinalizationFilePromoter : IFinalizationFilePromoter
{
    private const int BufferSize = 1024 * 1024;
    private readonly FinalizationJournalStore _journalStore;
    private readonly Action<string, string, bool> _moveFile;

    public FinalizationFilePromoter(FinalizationJournalStore journalStore)
        : this(journalStore, static (source, destination, overwrite) => File.Move(source, destination, overwrite))
    {
    }

    internal FinalizationFilePromoter(
        FinalizationJournalStore journalStore,
        Action<string, string, bool> moveFile)
    {
        _journalStore = journalStore;
        _moveFile = moveFile;
    }

    public async Task<FinalizationPromotionResult> PromoteAsync(
        string sourcePath,
        string destinationPath,
        FinalizationMarker marker,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(sourcePath);
        ArgumentException.ThrowIfNullOrWhiteSpace(destinationPath);
        ArgumentNullException.ThrowIfNull(marker);
        cancellationToken.ThrowIfCancellationRequested();

        if (!File.Exists(sourcePath))
        {
            throw new FileNotFoundException("The completed transfer source is missing.", sourcePath);
        }
        long sourceLength = new FileInfo(sourcePath).Length;
        if (sourceLength != marker.ExpectedLength)
        {
            throw new InvalidDataException(
                $"Finalization expected {marker.ExpectedLength} bytes, but the source contains {sourceLength} bytes.");
        }

        FinalizationMarker promotionStarted = marker with
        {
            Stage = FinalizationStage.PromotionStarted,
            SourcePath = sourcePath,
            StagingPath = null
        };
        await _journalStore.SaveAsync(destinationPath, promotionStarted, cancellationToken).ConfigureAwait(false);

        try
        {
            _moveFile(sourcePath, destinationPath, true);
            await _journalStore.SaveAsync(
                destinationPath,
                promotionStarted with { Stage = FinalizationStage.DestinationCommitted },
                cancellationToken).ConfigureAwait(false);
            return new FinalizationPromotionResult(destinationPath, false, sourceLength);
        }
        catch (IOException) when (File.Exists(sourcePath))
        {
            string stagingPath = TransferArtifactPaths.GetFinalizationStagingPath(destinationPath);
            FinalizationMarker copying = promotionStarted with
            {
                Stage = FinalizationStage.CopyingToDestination,
                StagingPath = stagingPath
            };
            await _journalStore.SaveAsync(destinationPath, copying, cancellationToken).ConfigureAwait(false);
            if (File.Exists(stagingPath))
            {
                File.Delete(stagingPath);
            }

            await CopyDurablyAsync(sourcePath, stagingPath, cancellationToken).ConfigureAwait(false);
            await ValidateCandidateAsync(stagingPath, marker, cancellationToken).ConfigureAwait(false);
            FinalizationMarker ready = copying with { Stage = FinalizationStage.DestinationReady };
            await _journalStore.SaveAsync(destinationPath, ready, cancellationToken).ConfigureAwait(false);
            _moveFile(stagingPath, destinationPath, true);
            await _journalStore.SaveAsync(
                destinationPath,
                ready with { Stage = FinalizationStage.DestinationCommitted },
                cancellationToken).ConfigureAwait(false);
            if (File.Exists(sourcePath))
            {
                File.Delete(sourcePath);
            }
            return new FinalizationPromotionResult(destinationPath, true, sourceLength);
        }
    }

    internal static async Task ValidateCandidateAsync(
        string path,
        FinalizationMarker marker,
        CancellationToken cancellationToken)
    {
        if (!File.Exists(path))
        {
            throw new FileNotFoundException("The finalization candidate is missing.", path);
        }
        long length = new FileInfo(path).Length;
        if (length != marker.ExpectedLength)
        {
            throw new InvalidDataException(
                $"Finalization expected {marker.ExpectedLength} bytes, but found {length} bytes.");
        }
        if (!string.IsNullOrWhiteSpace(marker.ChecksumAlgorithm)
            && !string.IsNullOrWhiteSpace(marker.Checksum))
        {
            string actual = await DownloadChecksumService
                .ComputeAsync(path, marker.ChecksumAlgorithm, cancellationToken)
                .ConfigureAwait(false);
            if (!string.Equals(actual, marker.Checksum, StringComparison.OrdinalIgnoreCase))
            {
                throw new DownloadIntegrityException("The finalization candidate failed checksum verification.");
            }
        }
    }

    private static async Task CopyDurablyAsync(
        string sourcePath,
        string stagingPath,
        CancellationToken cancellationToken)
    {
        await using FileStream source = new(
            sourcePath,
            FileMode.Open,
            FileAccess.Read,
            FileShare.Read,
            BufferSize,
            FileOptions.Asynchronous | FileOptions.SequentialScan);
        await using FileStream destination = new(
            stagingPath,
            FileMode.CreateNew,
            FileAccess.Write,
            FileShare.None,
            BufferSize,
            FileOptions.Asynchronous | FileOptions.WriteThrough);
        await source.CopyToAsync(destination, BufferSize, cancellationToken).ConfigureAwait(false);
        await destination.FlushAsync(cancellationToken).ConfigureAwait(false);
        destination.Flush(flushToDisk: true);
    }
}
