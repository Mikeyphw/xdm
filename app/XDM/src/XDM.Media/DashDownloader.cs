using System.Diagnostics;

namespace XDM.Media;

internal sealed class DashDownloader(HttpClient httpClient)
{
    private const int MaximumSegmentBytes = 1024 * 1024 * 1024;

    public Task<StreamDownloadResult> DownloadAsync(
        MediaFormat format,
        string workspace,
        MediaRequestMetadata metadata,
        TimeSpan? liveDuration,
        IProgress<MediaDownloadProgress>? progress,
        CancellationToken cancellationToken)
        => DownloadAsync(format, workspace, metadata, liveDuration, null, progress, cancellationToken);

    public async Task<StreamDownloadResult> DownloadAsync(
        MediaFormat format,
        string workspace,
        MediaRequestMetadata metadata,
        TimeSpan? liveDuration,
        long? maximumBytes,
        IProgress<MediaDownloadProgress>? progress,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(format);
        string representationId = string.IsNullOrWhiteSpace(format.ProviderData) ? format.Id : format.ProviderData;
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
        Stopwatch elapsed = Stopwatch.StartNew();
        bool dynamic = false;
        TimeSpan updatePeriod = TimeSpan.FromSeconds(5);

        while (true)
        {
            cancellationToken.ThrowIfCancellationRequested();
            if (LiveLimitReached(liveDuration, resume.LiveElapsedSeconds, elapsed.Elapsed))
            {
                break;
            }

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
            long order = 0;
            foreach (DashSegmentReference segment in segments)
            {
                cancellationToken.ThrowIfCancellationRequested();
                if (LiveLimitReached(liveDuration, resume.LiveElapsedSeconds, elapsed.Elapsed))
                {
                    break;
                }

                FragmentPlanEntry plan = CreateSegmentPlan(segment, order++);
                if (resume.TryReuse(plan))
                {
                    continue;
                }

                long currentBytes = resume.DownloadedBytes;
                long bytes = await FragmentRetryPolicy.ExecuteAsync(
                    token => MediaHttp.DownloadToFileAsync(
                        httpClient,
                        segment.Uri,
                        metadata,
                        Path.Combine(formatDirectory, plan.RelativePath),
                        null,
                        null,
                        MaximumSegmentDownloadLimit(maximumBytes, currentBytes),
                        token),
                    cancellationToken).ConfigureAwait(false);
                resume.Complete(plan, bytes);
                await checkpointStore.SaveAsync(
                    resume.ToCheckpoint(
                        format.ManifestUri.AbsoluteUri,
                        format.Id,
                        CreatePlanId(format, representation, manifest),
                        resume.LiveElapsedSeconds + elapsed.Elapsed.TotalSeconds,
                        DateTimeOffset.UtcNow),
                    cancellationToken).ConfigureAwait(false);
                int mediaCount = resume.EntriesInOrderSnapshot().Count(static entry => !entry.Id.Equals("init", StringComparison.Ordinal));
                int totalMedia = segments.Count(static segment => !segment.IsInitialization);
                progress?.Report(new MediaDownloadProgress(
                    dynamic ? "Downloading live DASH" : "Downloading DASH",
                    mediaCount,
                    dynamic ? null : totalMedia,
                    resume.DownloadedBytes,
                    $"Downloaded DASH fragment {segment.Id}."));
            }

            if (!manifest.IsDynamic || LiveLimitReached(liveDuration, resume.LiveElapsedSeconds, elapsed.Elapsed))
            {
                break;
            }

            if (liveDuration is TimeSpan limit)
            {
                TimeSpan remaining = limit - TimeSpan.FromSeconds(resume.LiveElapsedSeconds) - elapsed.Elapsed;
                if (remaining <= TimeSpan.Zero)
                {
                    break;
                }

                updatePeriod = remaining < updatePeriod ? remaining : updatePeriod;
            }

            await Task.Delay(updatePeriod, cancellationToken).ConfigureAwait(false);
        }

        string outputPath = Path.Combine(formatDirectory, "stream.bin");
        await FragmentAssembler.AssembleAsync(
            resume.ExistingPathsInOrder(),
            outputPath,
            "No DASH fragments were downloaded.",
            cancellationToken).ConfigureAwait(false);
        int fragmentCount = resume.EntriesInOrderSnapshot().Count(static entry => !entry.Id.Equals("init", StringComparison.Ordinal));
        return new StreamDownloadResult(outputPath, fragmentCount, resume.DownloadedBytes, dynamic);
    }

    private static FragmentPlanEntry CreateSegmentPlan(DashSegmentReference segment, long order)
    {
        string identity = FragmentIdentity.Create(segment.Uri, contentKey: segment.IsInitialization ? "dash-init-v2" : "dash-segment-v2");
        string fileName = segment.IsInitialization
            ? $"00000000000000000000-init-{identity[..16].ToLowerInvariant()}.part"
            : FragmentIdentity.StableFileName(segment.Id, identity, "dash");
        return new FragmentPlanEntry(
            segment.Id,
            segment.Uri,
            segment.IsInitialization ? long.MinValue : order,
            Path.Combine("fragments", fileName),
            identity,
            IsInitialization: segment.IsInitialization);
    }

    private static string CreatePlanId(MediaFormat format, DashRepresentation representation, DashManifest manifest)
        => FragmentIdentity.ShortHash(
            "dash",
            format.ManifestUri.AbsoluteUri,
            format.Id,
            representation.Id,
            manifest.IsDynamic.ToString(),
            manifest.Duration?.ToString(),
            manifest.MinimumUpdatePeriod.ToString());

    private static long? MaximumSegmentDownloadLimit(long? maximumBytes, long downloadedBytes)
    {
        long remaining = maximumBytes is long limit
            ? limit - downloadedBytes
            : MaximumSegmentBytes;
        if (remaining <= 0)
        {
            throw new InvalidDataException($"Media capture exceeded the configured {maximumBytes} byte limit.");
        }

        return Math.Min(remaining, MaximumSegmentBytes);
    }

    private static bool LiveLimitReached(TimeSpan? liveDuration, double previousSeconds, TimeSpan elapsed)
        => liveDuration is TimeSpan limit
            && TimeSpan.FromSeconds(previousSeconds) + elapsed >= limit;
}
