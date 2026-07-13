using System.Globalization;
using System.Net;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text.Json;

namespace XDM.DownloadEngine;

public sealed class PartialFileRepairService(HttpClient httpClient) : IPartialFileRepairService
{
    private const int MinimumBlockSize = 64 * 1024;
    private const int MaximumBlockSize = 16 * 1024 * 1024;
    private readonly HttpClient _httpClient = httpClient ?? throw new ArgumentNullException(nameof(httpClient));

    public async Task CaptureVerifiedStateAsync(
        string localPath,
        long expectedLength,
        string? entityTag,
        DateTimeOffset? lastModified,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(localPath);
        ArgumentOutOfRangeException.ThrowIfNegative(expectedLength);

        FileInfo file = new(localPath);
        if (!file.Exists)
        {
            throw new FileNotFoundException("The verified file is unavailable.", localPath);
        }
        if (file.Length != expectedLength)
        {
            throw new DownloadIntegrityException(
                $"The verified file length changed. Expected {expectedLength}; received {file.Length}.");
        }

        const int blockSize = 4 * 1024 * 1024;
        Dictionary<long, string> hashes = new();
        byte[] buffer = new byte[blockSize];
        await using FileStream stream = new(
            localPath,
            FileMode.Open,
            FileAccess.Read,
            FileShare.Read,
            128 * 1024,
            FileOptions.Asynchronous | FileOptions.SequentialScan);
        for (long start = 0; start < expectedLength; start += blockSize)
        {
            cancellationToken.ThrowIfCancellationRequested();
            int expectedCount = checked((int)Math.Min(blockSize, expectedLength - start));
            int read = 0;
            while (read < expectedCount)
            {
                int count = await stream.ReadAsync(
                    buffer.AsMemory(read, expectedCount - read),
                    cancellationToken).ConfigureAwait(false);
                if (count == 0)
                {
                    throw new EndOfStreamException("The verified file ended while its repair manifest was being created.");
                }
                read += count;
            }
            hashes[start] = Convert.ToHexString(SHA256.HashData(buffer.AsSpan(0, expectedCount)));
        }

        await SaveManifestAsync(
            localPath,
            new RepairBlockManifest(
                RepairBlockManifest.CurrentVersion,
                expectedLength,
                blockSize,
                NormalizeStrongEntityTag(entityTag),
                lastModified,
                hashes,
                DateTimeOffset.UtcNow),
            cancellationToken).ConfigureAwait(false);
    }

    public async Task<PartialFileRepairResult> RepairAsync(
        PartialFileRepairRequest request,
        IProgress<(long Processed, long Total)>? progress = null,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(request);
        if (request.ExpectedLength <= 0)
        {
            throw new ArgumentOutOfRangeException(nameof(request), "Expected length must be positive.");
        }

        int blockSize = Math.Clamp(request.BlockSizeBytes, MinimumBlockSize, MaximumBlockSize);
        Directory.CreateDirectory(Path.GetDirectoryName(request.LocalPath) ?? ".");
        RemoteIdentity remoteIdentity = await ValidateRemoteIdentityAsync(request, cancellationToken)
            .ConfigureAwait(false);
        bool hasRemoteValidator = remoteIdentity.EntityTag is not null
            || remoteIdentity.LastModified is not null;
        if (request.RequireRemoteValidator && !hasRemoteValidator)
        {
            throw new DownloadIntegrityException(
                "Selective repair without an expected checksum requires an ETag or Last-Modified validator.");
        }
        RepairBlockManifest? manifest = await LoadManifestAsync(request.LocalPath, cancellationToken).ConfigureAwait(false);
        bool manifestShapeMatches = manifest is not null
            && manifest.ExpectedLength == request.ExpectedLength
            && manifest.BlockSizeBytes == blockSize;
        bool validatorsMatch = hasRemoteValidator
            && string.Equals(manifest?.EntityTag, remoteIdentity.EntityTag, StringComparison.Ordinal)
            && manifest?.LastModified == remoteIdentity.LastModified;
        bool canUseManifest = manifestShapeMatches
            && (!request.RequireRemoteValidator || validatorsMatch);

        await using FileStream local = new(
            request.LocalPath,
            FileMode.OpenOrCreate,
            FileAccess.ReadWrite,
            FileShare.Read,
            128 * 1024,
            FileOptions.Asynchronous | FileOptions.RandomAccess | FileOptions.WriteThrough);
        if (local.Length > request.ExpectedLength)
        {
            local.SetLength(request.ExpectedLength);
        }

        List<RepairedByteRange> repaired = [];
        Dictionary<long, string> hashes = new();
        long downloaded = 0;
        long repairedBytes = 0;
        byte[] localBuffer = new byte[blockSize];
        for (long start = 0; start < request.ExpectedLength; start += blockSize)
        {
            cancellationToken.ThrowIfCancellationRequested();
            int expectedCount = checked((int)Math.Min(blockSize, request.ExpectedLength - start));
            int localCount = await ReadLocalBlockAsync(local, localBuffer, start, expectedCount, cancellationToken)
                .ConfigureAwait(false);
            string localHash = Convert.ToHexString(SHA256.HashData(localBuffer.AsSpan(0, localCount)));
            if (canUseManifest
                && localCount == expectedCount
                && manifest!.BlockHashes.TryGetValue(start, out string? savedHash)
                && string.Equals(localHash, savedHash, StringComparison.OrdinalIgnoreCase))
            {
                hashes[start] = savedHash;
                progress?.Report((start + expectedCount, request.ExpectedLength));
                continue;
            }

            byte[] remote = await DownloadRangeAsync(
                request,
                remoteIdentity,
                start,
                expectedCount,
                cancellationToken).ConfigureAwait(false);
            downloaded += remote.Length;
            string remoteHash = Convert.ToHexString(SHA256.HashData(remote));
            hashes[start] = remoteHash;
            bool matches = localCount == expectedCount
                && string.Equals(localHash, remoteHash, StringComparison.OrdinalIgnoreCase);
            if (!matches)
            {
                local.Position = start;
                await local.WriteAsync(remote, cancellationToken).ConfigureAwait(false);
                repaired.Add(new RepairedByteRange(start, start + remote.Length - 1));
                repairedBytes += remote.Length;
            }
            progress?.Report((start + expectedCount, request.ExpectedLength));
        }

        local.SetLength(request.ExpectedLength);
        await local.FlushAsync(cancellationToken).ConfigureAwait(false);
        local.Flush(flushToDisk: true);
        await SaveManifestAsync(
            request.LocalPath,
            new RepairBlockManifest(
                RepairBlockManifest.CurrentVersion,
                request.ExpectedLength,
                blockSize,
                remoteIdentity.EntityTag,
                remoteIdentity.LastModified,
                hashes,
                DateTimeOffset.UtcNow),
            cancellationToken).ConfigureAwait(false);
        return new PartialFileRepairResult(
            request.ExpectedLength,
            downloaded,
            repairedBytes,
            repaired,
            remoteIdentity.EntityTag,
            remoteIdentity.LastModified,
            canUseManifest);
    }

    private async Task<RemoteIdentity> ValidateRemoteIdentityAsync(
        PartialFileRepairRequest request,
        CancellationToken cancellationToken)
    {
        using HttpRequestMessage message = CreateRequest(request, 0, 1);
        using HttpResponseMessage response = await _httpClient.SendAsync(
            message,
            HttpCompletionOption.ResponseHeadersRead,
            cancellationToken).ConfigureAwait(false);
        if (response.StatusCode != HttpStatusCode.PartialContent)
        {
            throw new InvalidOperationException("Selective repair requires a server that honors byte-range requests.");
        }

        ContentRangeHeaderValue? range = response.Content.Headers.ContentRange;
        if (range?.Length != request.ExpectedLength)
        {
            throw new DownloadIntegrityException(
                $"The remote file length changed. Expected {request.ExpectedLength}; received {range?.Length?.ToString(CultureInfo.InvariantCulture) ?? "unknown"}.");
        }
        string? remoteTag = NormalizeStrongEntityTag(response.Headers.ETag?.ToString());
        string? expectedTag = NormalizeStrongEntityTag(request.EntityTag);
        if (expectedTag is not null
            && !string.Equals(remoteTag, expectedTag, StringComparison.Ordinal))
        {
            throw new DownloadIntegrityException("The remote entity tag changed; selective repair was blocked.");
        }
        DateTimeOffset? remoteModified = response.Content.Headers.LastModified;
        if (request.LastModified is DateTimeOffset expectedModified
            && remoteModified != expectedModified)
        {
            throw new DownloadIntegrityException("The remote Last-Modified validator changed; selective repair was blocked.");
        }
        return new RemoteIdentity(remoteTag, remoteModified);
    }

    private async Task<byte[]> DownloadRangeAsync(
        PartialFileRepairRequest request,
        RemoteIdentity remoteIdentity,
        long start,
        int count,
        CancellationToken cancellationToken)
    {
        using HttpRequestMessage message = CreateRequest(request, start, count);
        using HttpResponseMessage response = await _httpClient.SendAsync(
            message,
            HttpCompletionOption.ResponseHeadersRead,
            cancellationToken).ConfigureAwait(false);
        if (response.StatusCode != HttpStatusCode.PartialContent)
        {
            throw new InvalidOperationException("The server stopped honoring range requests during repair.");
        }
        ContentRangeHeaderValue? range = response.Content.Headers.ContentRange;
        long expectedEnd = start + count - 1;
        if (range?.From != start || range.To != expectedEnd || range.Length != request.ExpectedLength)
        {
            throw new DownloadIntegrityException("The server returned a contradictory Content-Range during repair.");
        }
        string? entityTag = NormalizeStrongEntityTag(response.Headers.ETag?.ToString());
        if (!string.IsNullOrWhiteSpace(remoteIdentity.EntityTag)
            && !string.Equals(entityTag, remoteIdentity.EntityTag, StringComparison.Ordinal))
        {
            throw new DownloadIntegrityException("The remote entity tag changed during selective repair.");
        }
        DateTimeOffset? lastModified = response.Content.Headers.LastModified;
        if (remoteIdentity.LastModified is DateTimeOffset expectedModified
            && lastModified != expectedModified)
        {
            throw new DownloadIntegrityException("The remote Last-Modified value changed during selective repair.");
        }
        byte[] bytes = await response.Content.ReadAsByteArrayAsync(cancellationToken).ConfigureAwait(false);
        if (bytes.Length != count)
        {
            throw new IOException($"The repair range ended early. Expected {count} bytes; received {bytes.Length}.");
        }
        return bytes;
    }

    private static HttpRequestMessage CreateRequest(PartialFileRepairRequest request, long start, int count)
    {
        HttpRequestMessage message = new(HttpMethod.Get, request.Source);
        HttpRequestMetadata.Apply(
            message,
            request.Headers,
            request.Username,
            request.Password,
            request.Cookie,
            request.Referer,
            request.UserAgent);
        message.Headers.Range = new RangeHeaderValue(start, start + count - 1);
        if (!string.IsNullOrWhiteSpace(request.EntityTag)
            && EntityTagHeaderValue.TryParse(request.EntityTag, out EntityTagHeaderValue? entityTag)
            && !entityTag.IsWeak)
        {
            message.Headers.IfRange = new RangeConditionHeaderValue(entityTag);
        }
        else if (request.LastModified is DateTimeOffset modified)
        {
            message.Headers.IfRange = new RangeConditionHeaderValue(modified);
        }
        return message;
    }

    private static async Task<int> ReadLocalBlockAsync(
        FileStream stream,
        byte[] buffer,
        long start,
        int expectedCount,
        CancellationToken cancellationToken)
    {
        if (start >= stream.Length)
        {
            return 0;
        }
        stream.Position = start;
        int total = 0;
        while (total < expectedCount)
        {
            int read = await stream.ReadAsync(buffer.AsMemory(total, expectedCount - total), cancellationToken)
                .ConfigureAwait(false);
            if (read == 0)
            {
                break;
            }
            total += read;
        }
        return total;
    }

    private static async Task<RepairBlockManifest?> LoadManifestAsync(
        string localPath,
        CancellationToken cancellationToken)
    {
        string path = TransferArtifactPaths.GetRepairManifestPath(localPath);
        if (!File.Exists(path))
        {
            return null;
        }
        try
        {
            await using FileStream stream = File.OpenRead(path);
            RepairBlockManifest? manifest = await JsonSerializer.DeserializeAsync<RepairBlockManifest>(
                stream,
                cancellationToken: cancellationToken).ConfigureAwait(false);
            return manifest is { Version: RepairBlockManifest.CurrentVersion } ? manifest : null;
        }
        catch (JsonException)
        {
            return null;
        }
    }

    private static async Task SaveManifestAsync(
        string localPath,
        RepairBlockManifest manifest,
        CancellationToken cancellationToken)
    {
        string path = TransferArtifactPaths.GetRepairManifestPath(localPath);
        string temporaryPath = $"{path}.tmp";
        try
        {
            await using (FileStream stream = new(
                temporaryPath,
                FileMode.Create,
                FileAccess.Write,
                FileShare.None,
                16 * 1024,
                FileOptions.Asynchronous | FileOptions.WriteThrough))
            {
                await JsonSerializer.SerializeAsync(stream, manifest, cancellationToken: cancellationToken)
                    .ConfigureAwait(false);
                await stream.FlushAsync(cancellationToken).ConfigureAwait(false);
                stream.Flush(flushToDisk: true);
            }
            File.Move(temporaryPath, path, overwrite: true);
        }
        finally
        {
            if (File.Exists(temporaryPath))
            {
                File.Delete(temporaryPath);
            }
        }
    }

    private static string? NormalizeStrongEntityTag(string? value)
    {
        if (string.IsNullOrWhiteSpace(value)
            || !EntityTagHeaderValue.TryParse(value, out EntityTagHeaderValue? parsed)
            || parsed.IsWeak)
        {
            return null;
        }
        return parsed.ToString();
    }

    private sealed record RemoteIdentity(string? EntityTag, DateTimeOffset? LastModified);

    private sealed record RepairBlockManifest(
        int Version,
        long ExpectedLength,
        int BlockSizeBytes,
        string? EntityTag,
        DateTimeOffset? LastModified,
        Dictionary<long, string> BlockHashes,
        DateTimeOffset UpdatedAt)
    {
        public const int CurrentVersion = 1;
    }
}
