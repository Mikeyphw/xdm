using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;

namespace XDM.Media;

public sealed class MediaCatalogService(HttpClient httpClient, IYtDlpProvider ytDlpProvider) : IMediaCatalogService
{
    private static readonly HashSet<string> DirectMediaExtensions = new(
        [".mp4", ".mkv", ".webm", ".mov", ".avi", ".mp3", ".m4a", ".aac", ".flac", ".ogg", ".wav", ".opus"],
        StringComparer.OrdinalIgnoreCase);

    private static readonly HashSet<string> DirectAudioExtensions = new(
        [".mp3", ".m4a", ".aac", ".flac", ".ogg", ".wav", ".opus"],
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
            try
            {
                MediaManifestResponse manifest = await MediaHttp.ReadManifestResponseAsync(
                    httpClient,
                    source,
                    requestMetadata,
                    cancellationToken).ConfigureAwait(false);
                return await CreateHlsCatalogAsync(
                    source,
                    manifest.FinalUri,
                    requestMetadata,
                    cancellationToken,
                    manifest.Content).ConfigureAwait(false);
            }
            catch (Exception exception) when (!cancellationToken.IsCancellationRequested && IsProviderFallbackEligible(exception))
            {
                MediaCatalog? providerAfterFailure = await TryGetExternalCatalogAsync(source, requestMetadata, cancellationToken).ConfigureAwait(false);
                return providerAfterFailure ?? UnknownCatalog(source, $"Native HLS discovery failed and provider fallback did not resolve this URL: {exception.Message}");
            }
        }

        if (extension.Equals(".mpd", StringComparison.OrdinalIgnoreCase))
        {
            try
            {
                MediaManifestResponse manifest = await MediaHttp.ReadManifestResponseAsync(
                    httpClient,
                    source,
                    requestMetadata,
                    cancellationToken).ConfigureAwait(false);
                return CreateDashCatalog(source, manifest.FinalUri, manifest.Content);
            }
            catch (Exception exception) when (!cancellationToken.IsCancellationRequested && IsProviderFallbackEligible(exception))
            {
                MediaCatalog? providerAfterFailure = await TryGetExternalCatalogAsync(source, requestMetadata, cancellationToken).ConfigureAwait(false);
                return providerAfterFailure ?? UnknownCatalog(source, $"Native DASH discovery failed and provider fallback did not resolve this URL: {exception.Message}");
            }
        }

        if (DirectMediaExtensions.Contains(extension))
        {
            return CreateDirectCatalog(source, null, Path.GetFileName(source.LocalPath));
        }

        HttpResponseMessage? response = null;
        try
        {
            using HttpRequestMessage request = MediaHttp.CreateRequest(HttpMethod.Get, source, requestMetadata);
            response = await httpClient
                .SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken)
                .ConfigureAwait(false);
            if (!response.IsSuccessStatusCode)
            {
                MediaCatalog? providerAfterFailure = await TryGetExternalCatalogAsync(source, requestMetadata, cancellationToken).ConfigureAwait(false);
                if (providerAfterFailure is not null)
                {
                    return providerAfterFailure;
                }

                response.EnsureSuccessStatusCode();
            }

            Uri finalUri = response.RequestMessage?.RequestUri ?? source;
            string? contentType = response.Content.Headers.ContentType?.MediaType;
            string? fileName = ResolveFileName(response.Content.Headers.ContentDisposition, finalUri);
            if (Contains(contentType, "mpegurl"))
            {
                string manifest = await MediaHttp.ReadManifestContentAsync(response, cancellationToken).ConfigureAwait(false);
                return await CreateHlsCatalogAsync(source, finalUri, requestMetadata, cancellationToken, manifest).ConfigureAwait(false);
            }

            if (Contains(contentType, "dash+xml"))
            {
                string manifest = await MediaHttp.ReadManifestContentAsync(response, cancellationToken).ConfigureAwait(false);
                return CreateDashCatalog(source, finalUri, manifest);
            }

            if (IsDirectMediaEvidence(contentType, fileName ?? finalUri.LocalPath))
            {
                return CreateDirectCatalog(finalUri, contentType, fileName);
            }
        }
        catch (Exception exception) when (!cancellationToken.IsCancellationRequested && IsProviderFallbackEligible(exception))
        {
            MediaCatalog? providerAfterFailure = await TryGetExternalCatalogAsync(source, requestMetadata, cancellationToken).ConfigureAwait(false);
            if (providerAfterFailure is not null)
            {
                return providerAfterFailure;
            }

            return UnknownCatalog(source, $"Media discovery failed before provider fallback could resolve this URL: {exception.Message}");
        }
        finally
        {
            response?.Dispose();
        }

        MediaCatalog? external = await TryGetExternalCatalogAsync(source, requestMetadata, cancellationToken).ConfigureAwait(false);
        return external ?? UnknownCatalog(
            source,
            "No supported media format was detected and yt-dlp is unavailable or did not recognize the page.");
    }

    private async Task<MediaCatalog> CreateHlsCatalogAsync(
        Uri catalogSource,
        Uri manifestUri,
        MediaRequestMetadata metadata,
        CancellationToken cancellationToken,
        string? knownManifest = null)
    {
        string content;
        Uri parseBase = manifestUri;
        if (knownManifest is null)
        {
            MediaManifestResponse response = await MediaHttp.ReadManifestResponseAsync(
                httpClient,
                manifestUri,
                metadata,
                cancellationToken).ConfigureAwait(false);
            content = response.Content;
            parseBase = response.FinalUri;
        }
        else
        {
            content = knownManifest;
        }

        HlsManifest manifest = HlsManifestParser.Parse(parseBase, content);
        List<MediaFormat> formats = [];
        bool live = !manifest.EndList;
        if (manifest.IsMaster)
        {
            foreach (HlsVariant variant in manifest.Variants)
            {
                bool hasSeparateAudio = !string.IsNullOrWhiteSpace(variant.AudioGroup)
                    && manifest.Renditions.Any(rendition =>
                        rendition.Type.Equals("AUDIO", StringComparison.OrdinalIgnoreCase)
                        && rendition.GroupId.Equals(variant.AudioGroup, StringComparison.Ordinal));
                string id = $"hls-video-{StableId(variant.Uri.AbsoluteUri, variant.AudioGroup, variant.SubtitleGroup, variant.Bandwidth?.ToString(System.Globalization.CultureInfo.InvariantCulture), variant.Codecs)}";
                formats.Add(new MediaFormat(
                    id,
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
                    formats.Count == 0,
                    false,
                    null,
                    variant.AudioGroup,
                    variant.SubtitleGroup));
            }

            foreach (HlsRendition rendition in manifest.Renditions.Where(static rendition => rendition.Uri is not null))
            {
                if (rendition.Type.Equals("AUDIO", StringComparison.OrdinalIgnoreCase))
                {
                    formats.Add(new MediaFormat(
                        $"hls-audio-{StableId(rendition.GroupId, rendition.Name, rendition.Language, rendition.Uri!.AbsoluteUri)}",
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
                        null,
                        rendition.GroupId));
                }
                else if (rendition.Type.Equals("SUBTITLES", StringComparison.OrdinalIgnoreCase))
                {
                    formats.Add(new MediaFormat(
                        $"hls-subtitle-{StableId(rendition.GroupId, rendition.Name, rendition.Language, rendition.Uri!.AbsoluteUri)}",
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
                        null,
                        null,
                        rendition.GroupId));
                }
            }

            live = await InferHlsMasterLiveStatusAsync(manifest, metadata, cancellationToken).ConfigureAwait(false);
        }
        else
        {
            bool encrypted = manifest.Segments.Any(static segment => segment.Key is not null);
            formats.Add(new MediaFormat(
                "hls-main",
                MediaStreamKind.Muxed,
                parseBase,
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
            catalogSource,
            MediaKind.Hls,
            Path.GetFileNameWithoutExtension(parseBase.LocalPath) is { Length: > 0 } title ? title : parseBase.Host,
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
        MediaManifestResponse manifest = await MediaHttp.ReadManifestResponseAsync(
            httpClient,
            source,
            metadata,
            cancellationToken).ConfigureAwait(false);
        return CreateDashCatalog(source, manifest.FinalUri, manifest.Content);
    }

    private static MediaCatalog CreateDashCatalog(Uri catalogSource, Uri manifestUri, string content)
    {
        DashManifest manifest = DashManifestParser.Parse(manifestUri, content);
        MediaFormat[] formats = manifest.Representations.Select(representation => new MediaFormat(
            $"dash-{representation.StreamKind.ToString().ToLowerInvariant()}-{StableId(representation.ScopedId)}",
            representation.StreamKind,
            manifestUri,
            representation.Container,
            representation.Codecs,
            representation.Bandwidth,
            representation.Width,
            representation.Height,
            representation.FrameRate,
            representation.Language,
            BuildDashDisplayName(representation),
            representation.IsDefault,
            representation.IsEncrypted,
            representation.ScopedId,
            null,
            null,
            representation.PeriodId,
            representation.AdaptationSetId,
            representation.Role)).ToArray();
        return new MediaCatalog(
            catalogSource,
            MediaKind.Dash,
            Path.GetFileNameWithoutExtension(manifestUri.LocalPath) is { Length: > 0 } title ? title : manifestUri.Host,
            manifest.IsDynamic,
            formats,
            $"DASH manifest with {formats.Length} selectable representation(s).",
            "native-dash",
            manifest.Duration);
    }

    private static MediaCatalog CreateDirectCatalog(Uri source, string? contentType, string? fileName)
    {
        string evidenceName = string.IsNullOrWhiteSpace(fileName) ? source.LocalPath : fileName;
        string extension = Path.GetExtension(evidenceName);
        MediaStreamKind kind = contentType?.StartsWith("audio/", StringComparison.OrdinalIgnoreCase) == true
            || DirectAudioExtensions.Contains(extension)
                ? MediaStreamKind.Audio
                : MediaStreamKind.Muxed;
        string? container = extension.Length > 1 ? extension.TrimStart('.') : null;
        MediaFormat format = new(
            "direct",
            kind,
            source,
            container,
            null,
            null,
            null,
            null,
            null,
            null,
            kind == MediaStreamKind.Audio ? "Direct audio" : "Direct media",
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

    private async Task<bool> InferHlsMasterLiveStatusAsync(
        HlsManifest manifest,
        MediaRequestMetadata metadata,
        CancellationToken cancellationToken)
    {
        bool sawProbe = false;
        bool anyLive = false;
        foreach (HlsVariant variant in manifest.Variants)
        {
            try
            {
                string variantManifest = await FragmentRetryPolicy.ExecuteAsync(
                    token => MediaHttp.ReadManifestAsync(httpClient, variant.Uri, metadata, token),
                    cancellationToken).ConfigureAwait(false);
                HlsManifest mediaManifest = HlsManifestParser.Parse(variant.Uri, variantManifest);
                sawProbe = true;
                anyLive |= !mediaManifest.EndList;
                if (anyLive)
                {
                    return true;
                }
            }
            catch (HttpRequestException)
            {
            }
            catch (InvalidDataException)
            {
            }
            catch (NotSupportedException)
            {
            }
        }

        return sawProbe && anyLive;
    }

    private static string BuildDashDisplayName(DashRepresentation representation)
    {
        List<string> parts = [];
        if (!string.IsNullOrWhiteSpace(representation.Name))
        {
            parts.Add(representation.Name);
        }

        parts.Add($"Period {representation.PeriodIndex + 1}");
        if (!string.IsNullOrWhiteSpace(representation.Role))
        {
            parts.Add(representation.Role);
        }

        return string.Join(" • ", parts);
    }

    private static bool IsDirectMediaEvidence(string? contentType, string evidenceName)
        => (contentType is not null
                && (contentType.StartsWith("video/", StringComparison.OrdinalIgnoreCase)
                    || contentType.StartsWith("audio/", StringComparison.OrdinalIgnoreCase)))
            || DirectMediaExtensions.Contains(Path.GetExtension(evidenceName));

    private async Task<MediaCatalog?> TryGetExternalCatalogAsync(
        Uri source,
        MediaRequestMetadata metadata,
        CancellationToken cancellationToken)
    {
        try
        {
            return await ytDlpProvider.TryGetCatalogAsync(source, metadata, cancellationToken).ConfigureAwait(false);
        }
        catch (Exception exception) when (!cancellationToken.IsCancellationRequested
            && (IsProviderFallbackEligible(exception) || exception is System.Text.Json.JsonException))
        {
            return UnknownCatalog(source, $"yt-dlp discovery failed: {exception.Message}");
        }
    }

    private static MediaCatalog UnknownCatalog(Uri source, string description)
        => new(
            source,
            MediaKind.Unknown,
            source.Host,
            false,
            [],
            description,
            "none");

    private static bool IsProviderFallbackEligible(Exception exception)
        => exception is HttpRequestException
            or TaskCanceledException
            or TimeoutException
            or IOException
            or UnauthorizedAccessException
            or InvalidOperationException
            or InvalidDataException
            or NotSupportedException;

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

    private static string StableId(params string?[] components)
    {
        string material = string.Join("\u001f", components.Where(static component => !string.IsNullOrWhiteSpace(component)));
        return Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(material))).ToLowerInvariant()[..16];
    }
}
