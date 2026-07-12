namespace XDM.Media;

internal sealed class ExternalFragmentDownloader(HttpClient httpClient)
{
    public async Task<StreamDownloadResult> DownloadAsync(
        MediaFormat format,
        ExternalMediaFormatData providerData,
        string workspace,
        MediaRequestMetadata metadata,
        IProgress<MediaDownloadProgress>? progress,
        CancellationToken cancellationToken)
    {
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

        IReadOnlyList<ExternalMediaFragment> fragments = providerData.Fragments.Count > 0
            ? providerData.Fragments
            : [new ExternalMediaFragment("fragment-0000000000", new Uri(providerData.DirectUrl))];
        foreach (ExternalMediaFragment fragment in fragments)
        {
            string partPath = Path.Combine(fragmentsDirectory, $"{Sanitize(fragment.Id)}.part");
            if (completed.Contains(fragment.Id) && File.Exists(partPath))
            {
                continue;
            }

            long bytes = await FragmentRetryPolicy.ExecuteAsync(
                token => MediaHttp.DownloadToFileAsync(
                    httpClient,
                    fragment.Uri,
                    metadata,
                    partPath,
                    null,
                    null,
                    token),
                cancellationToken).ConfigureAwait(false);
            downloadedBytes = checked(downloadedBytes + bytes);
            completed.Add(fragment.Id);
            await checkpointStore.SaveAsync(
                new FragmentCheckpoint(
                    format.ManifestUri.AbsoluteUri,
                    format.Id,
                    completed.Order(StringComparer.Ordinal).ToArray(),
                    downloadedBytes,
                    DateTimeOffset.UtcNow),
                cancellationToken).ConfigureAwait(false);
            progress?.Report(new MediaDownloadProgress(
                "Downloading extracted media",
                completed.Count,
                fragments.Count,
                downloadedBytes,
                $"Downloaded extracted fragment {completed.Count} of {fragments.Count}."));
        }

        string outputPath = Path.Combine(formatDirectory, "stream.bin");
        await AssembleAsync(fragmentsDirectory, outputPath, cancellationToken).ConfigureAwait(false);
        return new StreamDownloadResult(outputPath, fragments.Count, downloadedBytes, false);
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
            throw new InvalidDataException("No extracted media fragments were downloaded.");
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
