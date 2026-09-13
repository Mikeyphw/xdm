namespace XDM.Media;

internal sealed class ExternalFragmentDownloader(HttpClient httpClient)
{
    private const int MaximumFragmentBytes = 1024 * 1024 * 1024;

    public Task<StreamDownloadResult> DownloadAsync(
        MediaFormat format,
        ExternalMediaFormatData providerData,
        string workspace,
        MediaRequestMetadata metadata,
        IProgress<MediaDownloadProgress>? progress,
        CancellationToken cancellationToken)
        => DownloadAsync(format, providerData, workspace, metadata, null, progress, cancellationToken);

    public async Task<StreamDownloadResult> DownloadAsync(
        MediaFormat format,
        ExternalMediaFormatData providerData,
        string workspace,
        MediaRequestMetadata metadata,
        long? maximumBytes,
        IProgress<MediaDownloadProgress>? progress,
        CancellationToken cancellationToken)
    {
        string formatDirectory = FragmentIdentity.FormatDirectory(workspace, format);
        string fragmentsDirectory = Path.Combine(formatDirectory, "fragments");
        Directory.CreateDirectory(fragmentsDirectory);
        FragmentCheckpointStore checkpointStore = new(Path.Combine(formatDirectory, "checkpoint.json"));
        FragmentCheckpoint? checkpoint = await checkpointStore.LoadAsync(cancellationToken).ConfigureAwait(false);
        FragmentResumeState resume = FragmentResumeState.FromCheckpoint(
            checkpoint,
            format.ManifestUri.AbsoluteUri,
            format.Id,
            formatDirectory);

        IReadOnlyList<ExternalMediaFragment> fragments = providerData.Fragments.Count > 0
            ? providerData.Fragments
            : [new ExternalMediaFragment(CreateStableId(new Uri(providerData.DirectUrl), 0), new Uri(providerData.DirectUrl))];
        long order = 0;
        foreach (ExternalMediaFragment fragment in fragments)
        {
            FragmentPlanEntry plan = CreateFragmentPlan(fragment, order++);
            if (resume.TryReuse(plan))
            {
                continue;
            }

            long currentBytes = resume.DownloadedBytes;
            long bytes = await FragmentRetryPolicy.ExecuteAsync(
                token => MediaHttp.DownloadToFileAsync(
                    httpClient,
                    fragment.Uri,
                    metadata,
                    Path.Combine(formatDirectory, plan.RelativePath),
                    null,
                    null,
                    MaximumFragmentDownloadLimit(maximumBytes, currentBytes),
                    token),
                cancellationToken).ConfigureAwait(false);
            resume.Complete(plan, bytes);
            await checkpointStore.SaveAsync(
                resume.ToCheckpoint(
                    format.ManifestUri.AbsoluteUri,
                    format.Id,
                    FragmentIdentity.ShortHash("external", format.ManifestUri.AbsoluteUri, format.Id, providerData.Protocol),
                    resume.LiveElapsedSeconds,
                    DateTimeOffset.UtcNow),
                cancellationToken).ConfigureAwait(false);
            progress?.Report(new MediaDownloadProgress(
                "Downloading extracted media",
                resume.Count,
                fragments.Count,
                resume.DownloadedBytes,
                $"Downloaded extracted fragment {resume.Count} of {fragments.Count}."));
        }

        string outputPath = Path.Combine(formatDirectory, "stream.bin");
        await FragmentAssembler.AssembleAsync(
            resume.ExistingPathsInOrder(),
            outputPath,
            "No extracted media fragments were downloaded.",
            cancellationToken).ConfigureAwait(false);
        return new StreamDownloadResult(outputPath, fragments.Count, resume.DownloadedBytes, false);
    }

    internal static string CreateStableId(Uri uri, int occurrence)
        => occurrence == 0
            ? $"fragment-{FragmentIdentity.ShortHash(uri.AbsoluteUri)[..20].ToLowerInvariant()}"
            : $"fragment-{FragmentIdentity.ShortHash(uri.AbsoluteUri)[..20].ToLowerInvariant()}-{occurrence:D4}";

    private static FragmentPlanEntry CreateFragmentPlan(ExternalMediaFragment fragment, long order)
    {
        string identity = FragmentIdentity.Create(fragment.Uri, contentKey: "external-fragment-v2");
        return new FragmentPlanEntry(
            fragment.Id,
            fragment.Uri,
            order,
            Path.Combine("fragments", FragmentIdentity.StableFileName(fragment.Id, identity, "external")),
            identity);
    }

    private static long? MaximumFragmentDownloadLimit(long? maximumBytes, long downloadedBytes)
    {
        long remaining = maximumBytes is long limit
            ? limit - downloadedBytes
            : MaximumFragmentBytes;
        if (remaining <= 0)
        {
            throw new InvalidDataException($"Media capture exceeded the configured {maximumBytes} byte limit.");
        }

        return Math.Min(remaining, MaximumFragmentBytes);
    }
}
