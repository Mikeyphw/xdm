using System.Diagnostics;

namespace XDM.Media;

internal sealed class DashDownloader(HttpClient httpClient)
{
    private const int MaximumSegmentBytes = 1024 * 1024 * 1024;

    public async Task<StreamDownloadResult> DownloadAsync(
        MediaFormat format,
        string workspace,
        MediaRequestMetadata metadata,
        TimeSpan? liveDuration,
        IProgress<MediaDownloadProgress>? progress,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(format);
        string representationId = string.IsNullOrWhiteSpace(format.ProviderData) ? format.Id : format.ProviderData;
        string formatDirectory = Path.Combine(workspace, Sanitize(format.Id));
        string fragmentsDirectory = Path.Combine(formatDirectory, "fragments");
        Directory.CreateDirectory(fragmentsDirectory);
        FragmentCheckpointStore checkpointStore = new(Path.Combine(formatDirectory, "checkpoint.json"));
        FragmentCheckpoint? checkpoint = await checkpointStore.LoadAsync(cancellationToken).ConfigureAwait(false);
        HashSet<string> completed = checkpoint is not null
            && string.Equals(checkpoint.Source, format.ManifestUri.AbsoluteUri, StringComparison.Ordinal)
            && string.Equals(checkpoint.FormatId, format.Id, StringComparison.Ordinal)
                ? new HashSet<string>(checkpoint.CompletedIds, StringComparer.Ordinal)
                : new HashSet<string>(StringComparer.Ordinal);
        long downloadedBytes = checkpoint?.DownloadedBytes ?? 0;
        Stopwatch elapsed = Stopwatch.StartNew();
        bool dynamic = false;
        TimeSpan updatePeriod = TimeSpan.FromSeconds(5);

        while (true)
        {
            cancellationToken.ThrowIfCancellationRequested();
            string manifestText = await FragmentRetryPolicy.ExecuteAsync(
                token => MediaHttp.ReadManifestAsync(httpClient, format.ManifestUri, metadata, token),
                cancellationToken).ConfigureAwait(false);
            DashManifest manifest = DashManifestParser.Parse(format.ManifestUri, manifestText);
            dynamic = manifest.IsDynamic;
            updatePeriod = manifest.MinimumUpdatePeriod;
            DashRepresentation representation = manifest.Representations.FirstOrDefault(candidate =>
                string.Equals(candidate.Id, representationId, StringComparison.Ordinal))
                ?? throw new InvalidDataException($"DASH representation '{representationId}' is no longer present in the manifest.");
            IReadOnlyList<DashSegmentReference> segments = DashManifestParser.BuildSegments(
                representation,
                manifest,
                DateTimeOffset.UtcNow);
            foreach (DashSegmentReference segment in segments)
            {
                string id = Sanitize(segment.Id);
                string partPath = Path.Combine(
                    fragmentsDirectory,
                    segment.IsInitialization ? "00000000000000000000-init.part" : $"{id}.part");
                if (completed.Contains(segment.Id) && File.Exists(partPath))
                {
                    continue;
                }

                long bytes = await FragmentRetryPolicy.ExecuteAsync(
                    token => MediaHttp.DownloadToFileAsync(
                        httpClient,
                        segment.Uri,
                        metadata,
                        partPath,
                        null,
                        null,
                        token),
                    cancellationToken).ConfigureAwait(false);
                if (bytes > MaximumSegmentBytes)
                {
                    File.Delete(partPath);
                    throw new InvalidDataException("DASH segment exceeded the supported size limit.");
                }

                downloadedBytes = checked(downloadedBytes + bytes);
                completed.Add(segment.Id);
                await checkpointStore.SaveAsync(
                    new FragmentCheckpoint(
                        format.ManifestUri.AbsoluteUri,
                        format.Id,
                        completed.Order(StringComparer.Ordinal).ToArray(),
                        downloadedBytes,
                        DateTimeOffset.UtcNow),
                    cancellationToken).ConfigureAwait(false);
                int mediaCount = completed.Count(static id => !id.Equals("init", StringComparison.Ordinal));
                int totalMedia = segments.Count(static segment => !segment.IsInitialization);
                progress?.Report(new MediaDownloadProgress(
                    dynamic ? "Downloading live DASH" : "Downloading DASH",
                    mediaCount,
                    dynamic ? null : totalMedia,
                    downloadedBytes,
                    $"Downloaded DASH fragment {segment.Id}."));
            }

            if (!manifest.IsDynamic || (liveDuration is TimeSpan limit && elapsed.Elapsed >= limit))
            {
                break;
            }

            await Task.Delay(updatePeriod, cancellationToken).ConfigureAwait(false);
        }

        string outputPath = Path.Combine(formatDirectory, "stream.bin");
        await AssembleAsync(fragmentsDirectory, outputPath, cancellationToken).ConfigureAwait(false);
        int fragmentCount = completed.Count(static id => !id.Equals("init", StringComparison.Ordinal));
        return new StreamDownloadResult(outputPath, fragmentCount, downloadedBytes, dynamic);
    }

    private static async Task AssembleAsync(
        string fragmentsDirectory,
        string destinationPath,
        CancellationToken cancellationToken)
    {
        string[] fragments = Directory
            .EnumerateFiles(fragmentsDirectory, "*.part", SearchOption.TopDirectoryOnly)
            .Order(StringComparer.Ordinal)
            .ToArray();
        if (fragments.Length == 0)
        {
            throw new InvalidDataException("No DASH fragments were downloaded.");
        }

        string temporaryPath = $"{destinationPath}.assembling";
        await using (FileStream destination = new(
            temporaryPath,
            FileMode.Create,
            FileAccess.Write,
            FileShare.None,
            128 * 1024,
            FileOptions.Asynchronous | FileOptions.SequentialScan))
        {
            foreach (string fragment in fragments)
            {
                await using FileStream source = new(
                    fragment,
                    FileMode.Open,
                    FileAccess.Read,
                    FileShare.Read,
                    128 * 1024,
                    FileOptions.Asynchronous | FileOptions.SequentialScan);
                await source.CopyToAsync(destination, cancellationToken).ConfigureAwait(false);
            }

            await destination.FlushAsync(cancellationToken).ConfigureAwait(false);
        }

        File.Move(temporaryPath, destinationPath, overwrite: true);
    }

    private static string Sanitize(string value)
    {
        HashSet<char> invalid = new(Path.GetInvalidFileNameChars());
        string sanitized = new(value.Select(character => invalid.Contains(character) ? '_' : character).ToArray());
        return sanitized.Length == 0 ? "format" : sanitized;
    }
}
