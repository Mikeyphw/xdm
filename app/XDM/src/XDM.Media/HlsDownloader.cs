using System.Diagnostics;
using System.Security.Cryptography;
using System.Text;

namespace XDM.Media;

internal sealed class HlsDownloader(HttpClient httpClient)
{
    private readonly Dictionary<string, byte[]> _keyCache = new(StringComparer.Ordinal);
    private const int MaximumFragments = 1_000_000;
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
        bool observedLive = false;
        int targetDurationSeconds = 6;

        while (true)
        {
            cancellationToken.ThrowIfCancellationRequested();
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
            foreach (HlsSegment segment in manifest.Segments.OrderBy(static segment => segment.Sequence))
            {
                if (completed.Count >= MaximumFragments)
                {
                    throw new InvalidDataException("HLS download exceeded the supported fragment count.");
                }

                string id = segment.Sequence.ToString("D20", System.Globalization.CultureInfo.InvariantCulture);
                string partPath = Path.Combine(fragmentsDirectory, $"{id}.part");
                if (completed.Contains(id) && File.Exists(partPath))
                {
                    continue;
                }

                long bytes = await FragmentRetryPolicy.ExecuteAsync(
                    token => DownloadSegmentAsync(segment, partPath, formatDirectory, metadata, token),
                    cancellationToken).ConfigureAwait(false);
                downloadedBytes = checked(downloadedBytes + bytes);
                completed.Add(id);
                await checkpointStore.SaveAsync(
                    new FragmentCheckpoint(
                        format.ManifestUri.AbsoluteUri,
                        format.Id,
                        completed.Order(StringComparer.Ordinal).ToArray(),
                        downloadedBytes,
                        DateTimeOffset.UtcNow),
                    cancellationToken).ConfigureAwait(false);
                progress?.Report(new MediaDownloadProgress(
                    currentManifestIsLive ? "Downloading live HLS" : "Downloading HLS",
                    completed.Count,
                    manifest.EndList ? manifest.Segments.Count : null,
                    downloadedBytes,
                    $"Downloaded HLS fragment {segment.Sequence}."));
            }

            if (manifest.EndList || (liveDuration is TimeSpan limit && elapsed.Elapsed >= limit))
            {
                break;
            }

            TimeSpan refreshDelay = TimeSpan.FromSeconds(Math.Clamp(targetDurationSeconds / 2.0, 1, 30));
            await Task.Delay(refreshDelay, cancellationToken).ConfigureAwait(false);
        }

        string outputPath = Path.Combine(formatDirectory, "stream.bin");
        await AssembleAsync(fragmentsDirectory, outputPath, cancellationToken).ConfigureAwait(false);
        return new StreamDownloadResult(outputPath, completed.Count, downloadedBytes, observedLive);
    }

    private async Task<long> DownloadSegmentAsync(
        HlsSegment segment,
        string destinationPath,
        string formatDirectory,
        MediaRequestMetadata metadata,
        CancellationToken cancellationToken)
    {
        byte[] encrypted = await MediaHttp.ReadBytesAsync(
            httpClient,
            segment.Uri,
            metadata,
            MaximumSegmentBytes,
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
        (byte[]? initialization, string? mapMarkerPath) = await GetInitializationBytesAsync(
            segment,
            formatDirectory,
            metadata,
            cancellationToken).ConfigureAwait(false);
        string temporaryPath = $"{destinationPath}.downloading";
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
        if (mapMarkerPath is not null)
        {
            await File.WriteAllTextAsync(mapMarkerPath, segment.InitializationMap!.Uri.AbsoluteUri, cancellationToken).ConfigureAwait(false);
        }

        return payload.LongLength + (initialization?.LongLength ?? 0);
    }

    private async Task<(byte[]? Bytes, string? MarkerPath)> GetInitializationBytesAsync(
        HlsSegment segment,
        string formatDirectory,
        MediaRequestMetadata metadata,
        CancellationToken cancellationToken)
    {
        if (segment.InitializationMap is not HlsInitializationMap map)
        {
            return (null, null);
        }

        string mapId = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(
            $"{map.Uri.AbsoluteUri}|{map.ByteRangeOffset}|{map.ByteRangeLength}")))[..16];
        string markerPath = Path.Combine(formatDirectory, $"map-{mapId}.used");
        if (File.Exists(markerPath))
        {
            return (null, null);
        }

        byte[] bytes = await FragmentRetryPolicy.ExecuteAsync(
            token => MediaHttp.ReadBytesAsync(
                httpClient,
                map.Uri,
                metadata,
                MaximumSegmentBytes,
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

        return (bytes, markerPath);
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
        try
        {
            using ICryptoTransform decryptor = aes.CreateDecryptor();
            return decryptor.TransformFinalBlock(encrypted, 0, encrypted.Length);
        }
        catch (CryptographicException)
        {
            aes.Padding = PaddingMode.None;
            using ICryptoTransform decryptor = aes.CreateDecryptor();
            return decryptor.TransformFinalBlock(encrypted, 0, encrypted.Length);
        }
    }

    private static byte[] CreateSequenceInitializationVector(long sequence)
    {
        byte[] iv = new byte[16];
        System.Buffers.Binary.BinaryPrimitives.WriteInt64BigEndian(iv.AsSpan(8), sequence);
        return iv;
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
            throw new InvalidDataException("No HLS fragments were downloaded.");
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
