using System.Net.Http.Headers;

namespace XDM.Media;

public sealed class MediaCatalogService(HttpClient httpClient, IYtDlpProvider ytDlpProvider) : IMediaCatalogService
{
    private static readonly HashSet<string> DirectMediaExtensions = new(
        [".mp4", ".mkv", ".webm", ".mov", ".avi", ".mp3", ".m4a", ".aac", ".flac", ".ogg", ".wav", ".opus"],
        StringComparer.OrdinalIgnoreCase);

    public async Task<MediaCatalog> GetCatalogAsync(
        Uri source,
        MediaRequestMetadata? metadata = null,
        CancellationToken cancellationToken = default)
    {
        ValidateSource(source);
        MediaRequestMetadata requestMetadata = metadata ?? MediaRequestMetadata.Empty;
        string extension = Path.GetExtension(source.AbsolutePath);
        if (extension.Equals(".m3u8", StringComparison.OrdinalIgnoreCase))
        {
            return await CreateHlsCatalogAsync(source, requestMetadata, cancellationToken).ConfigureAwait(false);
        }

        if (extension.Equals(".mpd", StringComparison.OrdinalIgnoreCase))
        {
            return await CreateDashCatalogAsync(source, requestMetadata, cancellationToken).ConfigureAwait(false);
        }

        if (DirectMediaExtensions.Contains(extension))
        {
            return CreateDirectCatalog(source, null, Path.GetFileName(source.LocalPath));
        }

        using HttpRequestMessage request = MediaHttp.CreateRequest(HttpMethod.Get, source, requestMetadata);
        using HttpResponseMessage response = await httpClient
            .SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken)
            .ConfigureAwait(false);
        response.EnsureSuccessStatusCode();
        string? contentType = response.Content.Headers.ContentType?.MediaType;
        string? fileName = ResolveFileName(response.Content.Headers.ContentDisposition, source);
        if (Contains(contentType, "mpegurl"))
        {
            string manifest = await ReadBoundedContentAsync(response, cancellationToken).ConfigureAwait(false);
            return await CreateHlsCatalogAsync(source, requestMetadata, cancellationToken, manifest).ConfigureAwait(false);
        }

        if (Contains(contentType, "dash+xml"))
        {
            string manifest = await ReadBoundedContentAsync(response, cancellationToken).ConfigureAwait(false);
            return CreateDashCatalog(source, manifest);
        }

        if (contentType is not null
            && (contentType.StartsWith("video/", StringComparison.OrdinalIgnoreCase)
                || contentType.StartsWith("audio/", StringComparison.OrdinalIgnoreCase)))
        {
            return CreateDirectCatalog(source, contentType, fileName);
        }

        MediaCatalog? external = await ytDlpProvider
            .TryGetCatalogAsync(source, requestMetadata, cancellationToken)
            .ConfigureAwait(false);
        return external ?? new MediaCatalog(
            source,
            MediaKind.Unknown,
            source.Host,
            false,
            [],
            "No supported media format was detected and yt-dlp is unavailable or did not recognize the page.",
            "none");
    }

    private async Task<MediaCatalog> CreateHlsCatalogAsync(
        Uri source,
        MediaRequestMetadata metadata,
        CancellationToken cancellationToken,
        string? knownManifest = null)
    {
        string content = knownManifest ?? await FragmentRetryPolicy.ExecuteAsync(
            token => MediaHttp.ReadManifestAsync(httpClient, source, metadata, token),
            cancellationToken).ConfigureAwait(false);
        HlsManifest manifest = HlsManifestParser.Parse(source, content);
        List<MediaFormat> formats = [];
        bool live = !manifest.EndList;
        if (manifest.IsMaster)
        {
            int variantIndex = 0;
            foreach (HlsVariant variant in manifest.Variants)
            {
                bool hasSeparateAudio = !string.IsNullOrWhiteSpace(variant.AudioGroup)
                    && manifest.Renditions.Any(rendition =>
                        rendition.Type.Equals("AUDIO", StringComparison.OrdinalIgnoreCase)
                        && rendition.GroupId.Equals(variant.AudioGroup, StringComparison.Ordinal));
                formats.Add(new MediaFormat(
                    $"hls-video-{variantIndex++}",
                    hasSeparateAudio ? MediaStreamKind.Video : MediaStreamKind.Muxed,
                    variant.Uri,
                    "hls",
                    variant.Codecs,
                    variant.Bandwidth,
                    variant.Width,
                    variant.Height,
                    variant.FrameRate,
                    null,
                    variant.Name,
                    variantIndex == 1,
                    false));
            }

            int audioIndex = 0;
            int subtitleIndex = 0;
            foreach (HlsRendition rendition in manifest.Renditions.Where(static rendition => rendition.Uri is not null))
            {
                if (rendition.Type.Equals("AUDIO", StringComparison.OrdinalIgnoreCase))
                {
                    formats.Add(new MediaFormat(
                        $"hls-audio-{audioIndex++}",
                        MediaStreamKind.Audio,
                        rendition.Uri!,
                        "hls",
                        null,
                        null,
                        null,
                        null,
                        null,
                        rendition.Language,
                        rendition.Name,
                        rendition.IsDefault,
                        false,
                        rendition.GroupId));
                }
                else if (rendition.Type.Equals("SUBTITLES", StringComparison.OrdinalIgnoreCase))
                {
                    formats.Add(new MediaFormat(
                        $"hls-subtitle-{subtitleIndex++}",
                        MediaStreamKind.Subtitle,
                        rendition.Uri!,
                        "webvtt",
                        null,
                        null,
                        null,
                        null,
                        null,
                        rendition.Language,
                        rendition.Name,
                        rendition.IsDefault,
                        false,
                        rendition.GroupId));
                }
            }

            if (manifest.Variants.Count > 0)
            {
                try
                {
                    string firstVariant = await FragmentRetryPolicy.ExecuteAsync(
                        token => MediaHttp.ReadManifestAsync(httpClient, manifest.Variants[0].Uri, metadata, token),
                        cancellationToken).ConfigureAwait(false);
                    HlsManifest mediaManifest = HlsManifestParser.Parse(manifest.Variants[0].Uri, firstVariant);
                    live = !mediaManifest.EndList;
                }
                catch (HttpRequestException)
                {
                }
                catch (InvalidDataException)
                {
                }
            }
        }
        else
        {
            bool encrypted = manifest.Segments.Any(static segment => segment.Key is not null);
            formats.Add(new MediaFormat(
                "hls-main",
                MediaStreamKind.Muxed,
                source,
                "hls",
                null,
                null,
                null,
                null,
                null,
                null,
                "Main stream",
                true,
                encrypted));
        }

        return new MediaCatalog(
            source,
            MediaKind.Hls,
            Path.GetFileNameWithoutExtension(source.LocalPath) is { Length: > 0 } title ? title : source.Host,
            live,
            formats,
            $"HLS {(manifest.IsMaster ? "master" : "media")} playlist with {formats.Count} selectable format(s).",
            "native-hls");
    }

    private async Task<MediaCatalog> CreateDashCatalogAsync(
        Uri source,
        MediaRequestMetadata metadata,
        CancellationToken cancellationToken)
    {
        string content = await FragmentRetryPolicy.ExecuteAsync(
            token => MediaHttp.ReadManifestAsync(httpClient, source, metadata, token),
            cancellationToken).ConfigureAwait(false);
        return CreateDashCatalog(source, content);
    }

    private static MediaCatalog CreateDashCatalog(Uri source, string content)
    {
        DashManifest manifest = DashManifestParser.Parse(source, content);
        MediaFormat[] formats = manifest.Representations.Select(representation => new MediaFormat(
            $"dash-{representation.StreamKind.ToString().ToLowerInvariant()}-{representation.Id}",
            representation.StreamKind,
            source,
            representation.Container,
            representation.Codecs,
            representation.Bandwidth,
            representation.Width,
            representation.Height,
            representation.FrameRate,
            representation.Language,
            representation.Name,
            false,
            false,
            representation.Id)).ToArray();
        return new MediaCatalog(
            source,
            MediaKind.Dash,
            Path.GetFileNameWithoutExtension(source.LocalPath) is { Length: > 0 } title ? title : source.Host,
            manifest.IsDynamic,
            formats,
            $"DASH manifest with {formats.Length} selectable representation(s).",
            "native-dash");
    }

    private static MediaCatalog CreateDirectCatalog(Uri source, string? contentType, string? fileName)
    {
        MediaStreamKind kind = contentType?.StartsWith("audio/", StringComparison.OrdinalIgnoreCase) == true
            ? MediaStreamKind.Audio
            : MediaStreamKind.Muxed;
        MediaFormat format = new(
            "direct",
            kind,
            source,
            Path.GetExtension(source.AbsolutePath).TrimStart('.'),
            null,
            null,
            null,
            null,
            null,
            null,
            "Direct media",
            true,
            false);
        return new MediaCatalog(
            source,
            MediaKind.DirectFile,
            string.IsNullOrWhiteSpace(fileName) ? source.Host : fileName,
            false,
            [format],
            "Direct media response.",
            "direct");
    }

    private static async Task<string> ReadBoundedContentAsync(
        HttpResponseMessage response,
        CancellationToken cancellationToken)
    {
        await using Stream source = await response.Content.ReadAsStreamAsync(cancellationToken).ConfigureAwait(false);
        using MemoryStream destination = new();
        byte[] buffer = new byte[64 * 1024];
        int total = 0;
        while (true)
        {
            int read = await source.ReadAsync(buffer, cancellationToken).ConfigureAwait(false);
            if (read == 0)
            {
                break;
            }

            total = checked(total + read);
            if (total > MediaHttp.MaximumManifestBytes)
            {
                throw new InvalidDataException("Media manifest exceeded the configured safety limit.");
            }

            await destination.WriteAsync(buffer.AsMemory(0, read), cancellationToken).ConfigureAwait(false);
        }

        destination.Position = 0;
        using StreamReader reader = new(destination, detectEncodingFromByteOrderMarks: true);
        return await reader.ReadToEndAsync(cancellationToken).ConfigureAwait(false);
    }

    private static void ValidateSource(Uri source)
    {
        ArgumentNullException.ThrowIfNull(source);
        if (!source.IsAbsoluteUri || source.Scheme is not ("http" or "https"))
        {
            throw new ArgumentException("Media discovery requires an absolute HTTP or HTTPS URL.", nameof(source));
        }
    }

    private static string? ResolveFileName(ContentDispositionHeaderValue? contentDisposition, Uri source)
    {
        string? candidate = contentDisposition?.FileNameStar ?? contentDisposition?.FileName;
        if (!string.IsNullOrWhiteSpace(candidate))
        {
            return candidate.Trim().Trim('"');
        }

        string pathName = Path.GetFileName(source.LocalPath);
        return string.IsNullOrWhiteSpace(pathName) ? null : pathName;
    }

    private static bool Contains(string? value, string fragment)
        => value?.Contains(fragment, StringComparison.OrdinalIgnoreCase) == true;
}
