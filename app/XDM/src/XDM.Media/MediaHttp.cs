using System.Net;
using System.Net.Http.Headers;

namespace XDM.Media;

internal static class MediaHttp
{
    public const int MaximumManifestBytes = 8 * 1024 * 1024;
    public const int MaximumKeyBytes = 64 * 1024;

    public static HttpRequestMessage CreateRequest(
        HttpMethod method,
        Uri uri,
        MediaRequestMetadata metadata,
        long? rangeOffset = null,
        long? rangeLength = null)
    {
        HttpRequestMessage request = new(method, uri);
        if (rangeOffset is long offset)
        {
            long? end = rangeLength is long length ? checked(offset + length - 1) : null;
            request.Headers.Range = new RangeHeaderValue(offset, end);
        }

        ApplyMetadata(request, metadata);
        return request;
    }

    public static async Task<string> ReadManifestAsync(
        HttpClient client,
        Uri uri,
        MediaRequestMetadata metadata,
        CancellationToken cancellationToken)
    {
        using HttpRequestMessage request = CreateRequest(HttpMethod.Get, uri, metadata);
        using HttpResponseMessage response = await client
            .SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken)
            .ConfigureAwait(false);
        response.EnsureSuccessStatusCode();
        await using Stream stream = await response.Content.ReadAsStreamAsync(cancellationToken).ConfigureAwait(false);
        using MemoryStream buffer = new();
        await CopyBoundedAsync(stream, buffer, MaximumManifestBytes, cancellationToken).ConfigureAwait(false);
        buffer.Position = 0;
        using StreamReader reader = new(buffer, detectEncodingFromByteOrderMarks: true);
        return await reader.ReadToEndAsync(cancellationToken).ConfigureAwait(false);
    }

    public static async Task<byte[]> ReadBytesAsync(
        HttpClient client,
        Uri uri,
        MediaRequestMetadata metadata,
        int maximumBytes,
        long? rangeOffset,
        long? rangeLength,
        CancellationToken cancellationToken)
    {
        using HttpRequestMessage request = CreateRequest(
            HttpMethod.Get,
            uri,
            metadata,
            rangeOffset,
            rangeLength);
        using HttpResponseMessage response = await client
            .SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken)
            .ConfigureAwait(false);
        response.EnsureSuccessStatusCode();
        ValidateRangeResponse(response, rangeOffset);
        await using Stream stream = await response.Content.ReadAsStreamAsync(cancellationToken).ConfigureAwait(false);
        using MemoryStream buffer = new();
        await CopyBoundedAsync(stream, buffer, maximumBytes, cancellationToken).ConfigureAwait(false);
        byte[] bytes = buffer.ToArray();
        if (rangeLength is long expected && bytes.LongLength != expected)
        {
            throw new InvalidDataException($"Expected {expected} byte(s) but received {bytes.LongLength}.");
        }

        return bytes;
    }

    public static async Task<long> DownloadToFileAsync(
        HttpClient client,
        Uri uri,
        MediaRequestMetadata metadata,
        string destinationPath,
        long? rangeOffset,
        long? rangeLength,
        CancellationToken cancellationToken)
    {
        using HttpRequestMessage request = CreateRequest(
            HttpMethod.Get,
            uri,
            metadata,
            rangeOffset,
            rangeLength);
        using HttpResponseMessage response = await client
            .SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken)
            .ConfigureAwait(false);
        response.EnsureSuccessStatusCode();
        ValidateRangeResponse(response, rangeOffset);
        string fullPath = Path.GetFullPath(destinationPath);
        Directory.CreateDirectory(Path.GetDirectoryName(fullPath)!);
        string temporaryPath = $"{fullPath}.downloading";
        await using (Stream source = await response.Content.ReadAsStreamAsync(cancellationToken).ConfigureAwait(false))
        await using (FileStream destination = new(
            temporaryPath,
            FileMode.Create,
            FileAccess.Write,
            FileShare.None,
            128 * 1024,
            FileOptions.Asynchronous | FileOptions.SequentialScan))
        {
            await source.CopyToAsync(destination, cancellationToken).ConfigureAwait(false);
            await destination.FlushAsync(cancellationToken).ConfigureAwait(false);
        }

        long downloadedLength = new FileInfo(temporaryPath).Length;
        if (rangeLength is long expected && downloadedLength != expected)
        {
            File.Delete(temporaryPath);
            throw new InvalidDataException($"Expected {expected} byte(s) but received {downloadedLength}.");
        }

        File.Move(temporaryPath, fullPath, overwrite: true);
        return downloadedLength;
    }

    private static void ValidateRangeResponse(HttpResponseMessage response, long? rangeOffset)
    {
        if (rangeOffset is not null && response.StatusCode != HttpStatusCode.PartialContent)
        {
            throw new InvalidDataException("The media server ignored the requested byte range.");
        }
    }

    private static void ApplyMetadata(HttpRequestMessage request, MediaRequestMetadata metadata)
    {
        if (!string.IsNullOrWhiteSpace(metadata.UserAgent))
        {
            request.Headers.TryAddWithoutValidation("User-Agent", metadata.UserAgent);
        }

        if (!string.IsNullOrWhiteSpace(metadata.Referer)
            && Uri.TryCreate(metadata.Referer, UriKind.Absolute, out Uri? referer)
            && referer.Scheme is "http" or "https")
        {
            request.Headers.Referrer = referer;
        }

        if (!string.IsNullOrWhiteSpace(metadata.Cookie))
        {
            request.Headers.TryAddWithoutValidation("Cookie", metadata.Cookie);
        }

        if (metadata.Headers is null)
        {
            return;
        }

        foreach ((string name, string value) in metadata.Headers)
        {
            if (!IsSafeHeader(name, value))
            {
                continue;
            }

            request.Headers.TryAddWithoutValidation(name, value);
        }
    }

    private static bool IsSafeHeader(string name, string value)
    {
        if (name.Length is 0 or > 64
            || value.Length > 8192
            || name.Contains('\r')
            || name.Contains('\n')
            || value.Contains('\r')
            || value.Contains('\n'))
        {
            return false;
        }

        return !name.Equals("Host", StringComparison.OrdinalIgnoreCase)
            && !name.Equals("Content-Length", StringComparison.OrdinalIgnoreCase)
            && !name.Equals("Transfer-Encoding", StringComparison.OrdinalIgnoreCase)
            && !name.Equals("Connection", StringComparison.OrdinalIgnoreCase)
            && !name.Equals("Range", StringComparison.OrdinalIgnoreCase)
            && !name.Equals("Cookie", StringComparison.OrdinalIgnoreCase)
            && !name.Equals("Referer", StringComparison.OrdinalIgnoreCase)
            && !name.Equals("User-Agent", StringComparison.OrdinalIgnoreCase);
    }

    private static async Task CopyBoundedAsync(
        Stream source,
        Stream destination,
        int maximumBytes,
        CancellationToken cancellationToken)
    {
        byte[] buffer = new byte[64 * 1024];
        int total = 0;
        while (true)
        {
            int read = await source.ReadAsync(buffer, cancellationToken).ConfigureAwait(false);
            if (read == 0)
            {
                return;
            }

            total = checked(total + read);
            if (total > maximumBytes)
            {
                throw new InvalidDataException("The response exceeded the configured safety limit.");
            }

            await destination.WriteAsync(buffer.AsMemory(0, read), cancellationToken).ConfigureAwait(false);
        }
    }
}
