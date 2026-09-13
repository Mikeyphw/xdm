using System.Diagnostics;
using System.Security.Cryptography;

namespace XDM.Media;

internal sealed class HlsDownloader(HttpClient httpClient)
{
    private readonly Dictionary<string, byte[]> _keyCache = new(StringComparer.Ordinal);
    private const int MaximumFragments = 1_000_000;
    private const int MaximumBufferedSegmentBytes = 64 * 1024 * 1024;

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
        bool observedLive = false;
        int targetDurationSeconds = 6;

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
            HlsManifest manifest = HlsManifestParser.Parse(format.ManifestUri, manifestText);
            if (manifest.IsMaster)
            {
                throw new InvalidDataException("The selected HLS format resolves to another master playlist.");
            }

            bool currentManifestIsLive = !manifest.EndList;
            observedLive |= currentManifestIsLive;
            targetDurationSeconds = manifest.TargetDurationSeconds;
            int manifestSegmentCount = manifest.Segments.Count;
            foreach (HlsSegment segment in manifest.Segments.OrderBy(static segment => segment.Sequence))
            {
                cancellationToken.ThrowIfCancellationRequested();
                if (resume.Count >= MaximumFragments)
                {
                    throw new InvalidDataException("HLS download exceeded the supported fragment count.");
                }

                if (LiveLimitReached(liveDuration, resume.LiveElapsedSeconds, elapsed.Elapsed))
                {
                    break;
                }

                FragmentPlanEntry plan = CreateSegmentPlan(segment, resume);
                if (resume.TryReuse(plan))
                {
                    continue;
                }

                // Rebuild the plan after reuse inspection because a stale checkpoint entry
                // can be removed when its identity no longer matches the current manifest.
                // This keeps HLS initialization maps transactional: if the only prior init
                // owner was invalidated, the replacement segment carries the init map again.
                plan = CreateSegmentPlan(segment, resume);

                long currentBytes = resume.DownloadedBytes;
                long? remainingBytes = RemainingLimit(maximumBytes, currentBytes);
                long bytes = await FragmentRetryPolicy.ExecuteAsync(
                    token => DownloadSegmentAsync(segment, plan, formatDirectory, metadata, remainingBytes, token),
                    cancellationToken).ConfigureAwait(false);
                EnsureWithinLimit(currentBytes, bytes, maximumBytes);
                resume.Complete(plan, bytes);
                await checkpointStore.SaveAsync(
                    resume.ToCheckpoint(
                        format.ManifestUri.AbsoluteUri,
                        format.Id,
                        CreatePlanId(format, manifest),
                        resume.LiveElapsedSeconds + elapsed.Elapsed.TotalSeconds,
                        DateTimeOffset.UtcNow),
                    cancellationToken).ConfigureAwait(false);
                progress?.Report(new MediaDownloadProgress(
                    currentManifestIsLive ? "Downloading live HLS" : "Downloading HLS",
                    resume.Count,
                    manifest.EndList ? manifestSegmentCount : null,
                    resume.DownloadedBytes,
                    $"Downloaded HLS fragment {segment.Sequence}."));
            }

            if (manifest.EndList || LiveLimitReached(liveDuration, resume.LiveElapsedSeconds, elapsed.Elapsed))
            {
                break;
            }

            TimeSpan refreshDelay = TimeSpan.FromSeconds(Math.Clamp(targetDurationSeconds / 2.0, 1, 30));
            if (liveDuration is TimeSpan limit)
            {
                TimeSpan remaining = limit - TimeSpan.FromSeconds(resume.LiveElapsedSeconds) - elapsed.Elapsed;
                if (remaining <= TimeSpan.Zero)
                {
                    break;
                }

                refreshDelay = remaining < refreshDelay ? remaining : refreshDelay;
            }

            await Task.Delay(refreshDelay, cancellationToken).ConfigureAwait(false);
        }

        string outputPath = Path.Combine(formatDirectory, "stream.bin");
        await FragmentAssembler.AssembleAsync(
            resume.ExistingPathsInOrder(),
            outputPath,
            "No HLS fragments were downloaded.",
            cancellationToken).ConfigureAwait(false);
        return new StreamDownloadResult(outputPath, resume.Count, resume.DownloadedBytes, observedLive);
    }

    private static FragmentPlanEntry CreateSegmentPlan(
        HlsSegment segment,
        FragmentResumeState resume)
    {
        string id = segment.Sequence.ToString("D20", System.Globalization.CultureInfo.InvariantCulture);
        string? initializationIdentity = CreateInitializationIdentity(segment.InitializationMap);
        bool includeInitialization = initializationIdentity is not null
            && (resume.EntryContainsInitialization(id, initializationIdentity)
                || !resume.ContainsInitialization(initializationIdentity));
        string identity = FragmentIdentity.Create(
            segment.Uri,
            segment.ByteRangeOffset,
            segment.ByteRangeLength,
            CreateContentKey(segment, includeInitialization, initializationIdentity));
        string fileName = FragmentIdentity.StableFileName(id, identity, "hls");
        return new FragmentPlanEntry(
            id,
            segment.Uri,
            segment.Sequence,
            Path.Combine("fragments", fileName),
            identity,
            segment.ByteRangeOffset,
            segment.ByteRangeLength,
            false,
            includeInitialization,
            initializationIdentity);
    }

    private static string CreatePlanId(MediaFormat format, HlsManifest manifest)
        => FragmentIdentity.ShortHash(
            "hls",
            format.ManifestUri.AbsoluteUri,
            format.Id,
            manifest.MediaSequence.ToString(System.Globalization.CultureInfo.InvariantCulture),
            manifest.EndList.ToString(),
            manifest.Segments.Count.ToString(System.Globalization.CultureInfo.InvariantCulture));

    private static string? CreateInitializationIdentity(HlsInitializationMap? map)
        => map is null
            ? null
            : FragmentIdentity.Create(map.Uri, map.ByteRangeOffset, map.ByteRangeLength, "hls-init-map");

    private static string CreateContentKey(
        HlsSegment segment,
        bool includeInitialization,
        string? initializationIdentity)
    {
        string keyIdentity = segment.Key is null
            ? "clear"
            : FragmentIdentity.ShortHash(
                segment.Key.Method,
                segment.Key.Uri.AbsoluteUri,
                segment.Key.InitializationVector is null ? null : Convert.ToHexString(segment.Key.InitializationVector));
        return string.Join('|',
            "hls-segment-v2",
            keyIdentity,
            segment.Discontinuity ? "discontinuity" : "continuous",
            includeInitialization ? initializationIdentity ?? "init" : "no-init");
    }

    private static int MaximumReadBytes(long? maximumBytes)
    {
        if (maximumBytes is not long limit)
        {
            return MaximumBufferedSegmentBytes;
        }

        if (limit <= 0)
        {
            throw new InvalidDataException("Media capture reached its configured size limit.");
        }

        return checked((int)Math.Min(limit, MaximumBufferedSegmentBytes));
    }

    private static long? RemainingLimit(long? maximumBytes, long downloadedBytes)
    {
        if (maximumBytes is not long limit)
        {
            return null;
        }

        long remaining = limit - downloadedBytes;
        if (remaining <= 0)
        {
            throw new InvalidDataException($"Media capture exceeded the configured {limit} byte limit.");
        }

        return remaining;
    }

    private static bool LiveLimitReached(TimeSpan? liveDuration, double previousSeconds, TimeSpan elapsed)
        => liveDuration is TimeSpan limit
            && TimeSpan.FromSeconds(previousSeconds) + elapsed >= limit;

    private static void EnsureWithinLimit(long downloadedBytes, long nextBytes, long? maximumBytes)
    {
        if (maximumBytes is long limit && checked(downloadedBytes + nextBytes) > limit)
        {
            throw new InvalidDataException($"Media capture exceeded the configured {limit} byte limit.");
        }
    }

    private async Task<long> DownloadSegmentAsync(
        HlsSegment segment,
        FragmentPlanEntry plan,
        string formatDirectory,
        MediaRequestMetadata metadata,
        long? maximumBytes,
        CancellationToken cancellationToken)
    {
        string destinationPath = Path.Combine(formatDirectory, plan.RelativePath);
        Directory.CreateDirectory(Path.GetDirectoryName(destinationPath)!);
        byte[]? initialization = plan.HasInitialization
            ? await GetInitializationBytesAsync(segment, metadata, cancellationToken).ConfigureAwait(false)
            : null;
        if (segment.Key is null && initialization is null)
        {
            return await MediaHttp.DownloadToFileAsync(
                httpClient,
                segment.Uri,
                metadata,
                destinationPath,
                segment.ByteRangeOffset,
                segment.ByteRangeLength,
                maximumBytes,
                cancellationToken).ConfigureAwait(false);
        }

        byte[] encrypted = await MediaHttp.ReadBytesAsync(
            httpClient,
            segment.Uri,
            metadata,
            MaximumReadBytes(maximumBytes),
            segment.ByteRangeOffset,
            segment.ByteRangeLength,
            cancellationToken).ConfigureAwait(false);
        byte[] payload = segment.Key is null
            ? encrypted
            : await DecryptPayloadAsync(
                segment.Key,
                segment.Sequence,
                encrypted,
                metadata,
                cancellationToken).ConfigureAwait(false);
        long totalLength = payload.LongLength + (initialization?.LongLength ?? 0);
        if (maximumBytes is long limit && totalLength > limit)
        {
            throw new InvalidDataException($"Media capture exceeded the configured {limit} byte limit.");
        }

        string temporaryPath = $"{destinationPath}.downloading";
        bool completed = false;
        try
        {
            await using (FileStream stream = new(
                temporaryPath,
                FileMode.Create,
                FileAccess.Write,
                FileShare.None,
                128 * 1024,
                FileOptions.Asynchronous | FileOptions.SequentialScan))
            {
                if (initialization is not null)
                {
                    await stream.WriteAsync(initialization, cancellationToken).ConfigureAwait(false);
                }

                await stream.WriteAsync(payload, cancellationToken).ConfigureAwait(false);
                await stream.FlushAsync(cancellationToken).ConfigureAwait(false);
            }

            File.Move(temporaryPath, destinationPath, overwrite: true);
            completed = true;
            return totalLength;
        }
        finally
        {
            if (!completed && File.Exists(temporaryPath))
            {
                File.Delete(temporaryPath);
            }
        }
    }

    private async Task<byte[]> GetInitializationBytesAsync(
        HlsSegment segment,
        MediaRequestMetadata metadata,
        CancellationToken cancellationToken)
    {
        HlsInitializationMap map = segment.InitializationMap
            ?? throw new InvalidDataException("HLS initialization map was not available for the selected segment.");
        byte[] bytes = await FragmentRetryPolicy.ExecuteAsync(
            token => MediaHttp.ReadBytesAsync(
                httpClient,
                map.Uri,
                metadata,
                MaximumBufferedSegmentBytes,
                map.ByteRangeOffset,
                map.ByteRangeLength,
                token),
            cancellationToken).ConfigureAwait(false);
        if (segment.Key is not null)
        {
            if (segment.Key.InitializationVector is null)
            {
                throw new NotSupportedException("Encrypted HLS initialization maps require an explicit IV.");
            }

            bytes = await DecryptPayloadAsync(
                segment.Key,
                segment.Sequence,
                bytes,
                metadata,
                cancellationToken).ConfigureAwait(false);
        }

        return bytes;
    }

    private async Task<byte[]> DecryptPayloadAsync(
        HlsEncryptionKey keyInfo,
        long sequence,
        byte[] encrypted,
        MediaRequestMetadata metadata,
        CancellationToken cancellationToken)
    {
        string keyId = keyInfo.Uri.AbsoluteUri;
        if (!_keyCache.TryGetValue(keyId, out byte[]? key) || key is null)
        {
            key = await FragmentRetryPolicy.ExecuteAsync(
                token => MediaHttp.ReadBytesAsync(
                    httpClient,
                    keyInfo.Uri,
                    metadata,
                    MediaHttp.MaximumKeyBytes,
                    null,
                    null,
                    token),
                cancellationToken).ConfigureAwait(false);
            _keyCache[keyId] = key;
        }

        if (key.Length != 16)
        {
            throw new InvalidDataException("HLS AES-128 key must contain exactly 16 bytes.");
        }

        byte[] iv = keyInfo.InitializationVector ?? CreateSequenceInitializationVector(sequence);
        using Aes aes = Aes.Create();
        aes.Key = key;
        aes.IV = iv;
        aes.Mode = CipherMode.CBC;
        aes.Padding = PaddingMode.PKCS7;
        using ICryptoTransform decryptor = aes.CreateDecryptor();
        return decryptor.TransformFinalBlock(encrypted, 0, encrypted.Length);
    }

    private static byte[] CreateSequenceInitializationVector(long sequence)
    {
        byte[] iv = new byte[16];
        System.Buffers.Binary.BinaryPrimitives.WriteInt64BigEndian(iv.AsSpan(8), sequence);
        return iv;
    }
}
