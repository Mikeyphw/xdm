using System.Diagnostics;
using System.Text.Json;

namespace XDM.Media;

public sealed class MediaDownloadService(
    HttpClient httpClient,
    IMediaCatalogService catalogService,
    IFfmpegService ffmpegService) : IMediaDownloadService
{
    public async Task<MediaDownloadResult> DownloadAsync(
        MediaDownloadRequest request,
        IProgress<MediaDownloadProgress>? progress = null,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(request);
        ValidateRequest(request);
        MediaRequestMetadata metadata = request.Metadata ?? MediaRequestMetadata.Empty;
        MediaCatalog catalog = await catalogService
            .GetCatalogAsync(request.Source, metadata, cancellationToken)
            .ConfigureAwait(false);
        if (catalog.Kind == MediaKind.Unknown || catalog.Formats.Count == 0)
        {
            throw new NotSupportedException(catalog.Description);
        }

        if (catalog.IsLive && request.LiveDuration is null)
        {
            throw new InvalidOperationException("Live media requires a bounded capture duration.");
        }

        MediaFormat? video = SelectVideo(catalog, request.VideoFormatId);
        MediaFormat? audio = SelectAudio(catalog, request.AudioFormatId, video);
        MediaFormat[] subtitles = request.SubtitleIds
            .Select(id => catalog.Formats.FirstOrDefault(format =>
                format.StreamKind == MediaStreamKind.Subtitle
                && string.Equals(format.Id, id, StringComparison.Ordinal)))
            .Where(static format => format is not null)
            .Cast<MediaFormat>()
            .DistinctBy(static format => format.Id, StringComparer.Ordinal)
            .ToArray();
        List<MediaFormat> mainFormats = [];
        if (video is not null)
        {
            mainFormats.Add(video);
        }

        if (audio is not null && !mainFormats.Any(format => string.Equals(format.Id, audio.Id, StringComparison.Ordinal)))
        {
            mainFormats.Add(audio);
        }

        if (mainFormats.Count == 0)
        {
            throw new InvalidOperationException("No downloadable video or audio format was selected.");
        }

        string destinationPath = Path.GetFullPath(request.DestinationPath);
        Directory.CreateDirectory(Path.GetDirectoryName(destinationPath)!);
        string workspace = CreateWorkspace(destinationPath, request, mainFormats, subtitles);
        Directory.CreateDirectory(workspace);
        FileStream? workspaceLock = null;
        Stopwatch stopwatch = Stopwatch.StartNew();
        List<StreamDownloadResult> mainStreams = [];
        List<PendingSubtitle> pendingSubtitles = [];
        List<string> subtitlePaths = [];
        long consumedBytes = 0;
        bool usedFfmpeg = false;
        bool completedSuccessfully = false;
        List<string> finalizationArtifacts = [];
        ScavengeAbandonedFinalizationFiles(Path.GetDirectoryName(destinationPath)!, TimeSpan.FromHours(12));
        try
        {
            workspaceLock = AcquireWorkspaceLock(workspace);
            int formatIndex = 0;
            foreach (MediaFormat format in mainFormats)
            {
                progress?.Report(new MediaDownloadProgress(
                    "Preparing media stream",
                    formatIndex,
                    mainFormats.Count,
                    mainStreams.Sum(static stream => stream.DownloadedBytes),
                    $"Preparing {format.DisplayName}."));
                StreamDownloadResult stream = await DownloadFormatAsync(
                    catalog.Kind,
                    format,
                    workspace,
                    metadata,
                    request.LiveDuration,
                    RemainingLimit(request.MaximumBytes, consumedBytes),
                    progress,
                    cancellationToken).ConfigureAwait(false);
                mainStreams.Add(stream);
                consumedBytes = checked(consumedBytes + stream.DownloadedBytes);
                formatIndex++;
            }

            HashSet<string> reservedSubtitlePaths = new(StringComparer.OrdinalIgnoreCase);
            foreach (MediaFormat subtitle in subtitles)
            {
                StreamDownloadResult subtitleStream = await DownloadFormatAsync(
                    catalog.Kind,
                    subtitle,
                    workspace,
                    metadata,
                    request.LiveDuration,
                    RemainingLimit(request.MaximumBytes, consumedBytes),
                    progress,
                    cancellationToken).ConfigureAwait(false);
                consumedBytes = checked(consumedBytes + subtitleStream.DownloadedBytes);
                pendingSubtitles.Add(new PendingSubtitle(
                    subtitleStream.Path,
                    CreateSubtitleDestination(destinationPath, subtitle, reservedSubtitlePaths)));
            }

            bool requiresMux = mainStreams.Count > 1
                || catalog.Kind is MediaKind.Hls or MediaKind.Dash
                || catalog.Kind == MediaKind.ExternalProvider
                    && mainFormats.Any(IsSegmentedExternalFormat);
            if (requiresMux)
            {
                ValidateMuxCompatibility(mainFormats, destinationPath);
                ExternalToolHealth health = await ffmpegService.GetHealthAsync(cancellationToken).ConfigureAwait(false);
                if (!health.IsAvailable)
                {
                    throw new InvalidOperationException($"FFmpeg is required to finalize this media selection. {health.Message}");
                }

                progress?.Report(new MediaDownloadProgress(
                    "Muxing",
                    mainStreams.Sum(static stream => stream.FragmentCount),
                    mainStreams.Sum(static stream => stream.FragmentCount),
                    mainStreams.Sum(static stream => stream.DownloadedBytes),
                    "Combining selected media streams without re-encoding."));
                string temporaryDestination = CreateFinalizationPath(destinationPath);
                finalizationArtifacts.Add(temporaryDestination);
                await ffmpegService.MuxAsync(
                    mainStreams.Select(static stream => stream.Path).ToArray(),
                    temporaryDestination,
                    cancellationToken).ConfigureAwait(false);
                ValidateFinalizationOutput(temporaryDestination);
                File.Move(temporaryDestination, destinationPath, overwrite: true);
                usedFfmpeg = true;
            }
            else
            {
                string temporaryDestination = CreateFinalizationPath(destinationPath);
                finalizationArtifacts.Add(temporaryDestination);
                File.Copy(mainStreams[0].Path, temporaryDestination, overwrite: true);
                ValidateFinalizationOutput(temporaryDestination);
                File.Move(temporaryDestination, destinationPath, overwrite: true);
            }

            foreach (PendingSubtitle subtitle in pendingSubtitles)
            {
                string temporarySubtitle = CreateFinalizationPath(subtitle.DestinationPath);
                finalizationArtifacts.Add(temporarySubtitle);
                File.Copy(subtitle.SourcePath, temporarySubtitle, overwrite: true);
                ValidateFinalizationOutput(temporarySubtitle);
                File.Move(temporarySubtitle, subtitle.DestinationPath, overwrite: true);
                subtitlePaths.Add(subtitle.DestinationPath);
            }

            stopwatch.Stop();
            progress?.Report(new MediaDownloadProgress(
                "Completed",
                mainStreams.Sum(static stream => stream.FragmentCount),
                mainStreams.Sum(static stream => stream.FragmentCount),
                mainStreams.Sum(static stream => stream.DownloadedBytes),
                $"Media saved to {destinationPath}."));
            completedSuccessfully = true;
            return new MediaDownloadResult(
                destinationPath,
                catalog.Kind,
                mainStreams.Sum(static stream => stream.FragmentCount),
                mainStreams.Sum(static stream => stream.DownloadedBytes),
                stopwatch.Elapsed,
                usedFfmpeg,
                subtitlePaths);
        }
        finally
        {
            workspaceLock?.Dispose();
            foreach (string artifact in finalizationArtifacts)
            {
                TryDeleteFile(artifact);
            }

            if (!request.KeepPartialFiles && completedSuccessfully)
            {
                TryDeleteDirectory(workspace);
            }
        }
    }

    private async Task<StreamDownloadResult> DownloadFormatAsync(
        MediaKind catalogKind,
        MediaFormat format,
        string workspace,
        MediaRequestMetadata metadata,
        TimeSpan? liveDuration,
        long? maximumBytes,
        IProgress<MediaDownloadProgress>? progress,
        CancellationToken cancellationToken)
    {
        if (catalogKind == MediaKind.Hls || IsHlsExternalFormat(format))
        {
            return await new HlsDownloader(httpClient).DownloadAsync(
                format,
                workspace,
                metadata,
                liveDuration,
                maximumBytes,
                progress,
                cancellationToken).ConfigureAwait(false);
        }

        if (catalogKind == MediaKind.Dash)
        {
            return await new DashDownloader(httpClient).DownloadAsync(
                format,
                workspace,
                metadata,
                liveDuration,
                maximumBytes,
                progress,
                cancellationToken).ConfigureAwait(false);
        }

        if (catalogKind == MediaKind.ExternalProvider
            && TryGetExternalData(format, out ExternalMediaFormatData externalData))
        {
            return await new ExternalFragmentDownloader(httpClient).DownloadAsync(
                format,
                externalData,
                workspace,
                metadata,
                maximumBytes,
                progress,
                cancellationToken).ConfigureAwait(false);
        }

        string formatDirectory = FragmentIdentity.FormatDirectory(workspace, format);
        Directory.CreateDirectory(formatDirectory);
        string outputPath = Path.Combine(formatDirectory, "stream.bin");
        long bytes = await FragmentRetryPolicy.ExecuteAsync(
            token => MediaHttp.DownloadToFileAsync(
                httpClient,
                format.ManifestUri,
                metadata,
                outputPath,
                null,
                null,
                maximumBytes,
                token),
            cancellationToken).ConfigureAwait(false);
        progress?.Report(new MediaDownloadProgress(
            "Downloading direct media",
            1,
            1,
            bytes,
            $"Downloaded {format.DisplayName}."));
        return new StreamDownloadResult(outputPath, 1, bytes, false);
    }


    internal static void ValidateMuxCompatibility(
        IReadOnlyList<MediaFormat> formats,
        string destinationPath)
    {
        string extension = Path.GetExtension(destinationPath).TrimStart('.').ToLowerInvariant();
        if (extension is "mkv" or "webm")
        {
            return;
        }

        if (extension is not ("mp4" or "m4v" or "mov"))
        {
            return;
        }

        foreach (MediaFormat format in formats)
        {
            string? incompatibleContainer = IncompatibleMp4Container(format.Container);
            if (incompatibleContainer is not null)
            {
                throw new InvalidDataException(
                    $"Cannot stream-copy {incompatibleContainer} media into {extension.ToUpperInvariant()}. Choose MKV or a transcode preset.");
            }

            foreach (string codec in NormalizeCodecs(format.Codecs))
            {
                if (IsKnownMp4IncompatibleCodec(codec))
                {
                    throw new InvalidDataException(
                        $"Cannot stream-copy codec '{codec}' into {extension.ToUpperInvariant()}. Choose MKV or a transcode preset.");
                }
            }
        }
    }

    private static IEnumerable<string> NormalizeCodecs(string? codecs)
    {
        if (string.IsNullOrWhiteSpace(codecs))
        {
            yield break;
        }

        foreach (string token in codecs.Split([',', ';', ' '], StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
        {
            string normalized = token.ToLowerInvariant();
            int profileSeparator = normalized.IndexOf('.');
            yield return profileSeparator > 0 ? normalized[..profileSeparator] : normalized;
        }
    }

    private static string? IncompatibleMp4Container(string? container)
    {
        string normalized = (container ?? string.Empty).Trim().TrimStart('.').ToLowerInvariant();
        return normalized switch
        {
            "webm" or "ogg" or "ogv" or "oga" or "opus" or "flac" => normalized,
            _ => null
        };
    }

    private static bool IsKnownMp4IncompatibleCodec(string codec)
        => codec is "vp8" or "vp9" or "vp09"
            or "opus" or "vorbis" or "theora" or "flac" or "dts";

    private static long ValidateFinalizationOutput(string path)
        => ConversionService.ValidateOutputFile(path);

    internal static int ScavengeAbandonedFinalizationFiles(
        string directory,
        TimeSpan minimumAge)
    {
        if (!Directory.Exists(directory))
        {
            return 0;
        }

        int deleted = 0;
        DateTime cutoffUtc = DateTime.UtcNow - minimumAge;
        foreach (string path in Directory.EnumerateFiles(directory, "*.xdm-finalizing*", SearchOption.TopDirectoryOnly))
        {
            try
            {
                if (File.GetLastWriteTimeUtc(path) <= cutoffUtc)
                {
                    File.Delete(path);
                    deleted++;
                }
            }
            catch (IOException)
            {
            }
            catch (UnauthorizedAccessException)
            {
            }
        }

        return deleted;
    }

    private static MediaFormat? SelectVideo(MediaCatalog catalog, string? requestedId)
    {
        if (!string.IsNullOrWhiteSpace(requestedId))
        {
            return catalog.Formats.FirstOrDefault(format =>
                (format.StreamKind is MediaStreamKind.Video or MediaStreamKind.Muxed)
                && string.Equals(format.Id, requestedId, StringComparison.Ordinal))
                ?? throw new InvalidOperationException($"Selected video format '{requestedId}' is no longer available.");
        }

        return catalog.Formats
            .Where(static format => format.StreamKind is MediaStreamKind.Video or MediaStreamKind.Muxed)
            .OrderByDescending(static format => format.IsDefault)
            .ThenByDescending(static format => format.Height ?? 0)
            .ThenByDescending(static format => format.Bandwidth ?? 0)
            .FirstOrDefault();
    }

    private static MediaFormat? SelectAudio(MediaCatalog catalog, string? requestedId, MediaFormat? video)
    {
        if (video?.StreamKind == MediaStreamKind.Muxed && string.IsNullOrWhiteSpace(requestedId))
        {
            return null;
        }

        if (!string.IsNullOrWhiteSpace(requestedId))
        {
            return catalog.Formats.FirstOrDefault(format =>
                format.StreamKind == MediaStreamKind.Audio
                && string.Equals(format.Id, requestedId, StringComparison.Ordinal))
                ?? throw new InvalidOperationException($"Selected audio format '{requestedId}' is no longer available.");
        }

        return catalog.Formats
            .Where(static format => format.StreamKind == MediaStreamKind.Audio)
            .OrderByDescending(static format => format.IsDefault)
            .ThenByDescending(static format => format.Bandwidth ?? 0)
            .FirstOrDefault();
    }

    private static bool IsHlsExternalFormat(MediaFormat format)
        => TryGetExternalData(format, out ExternalMediaFormatData data)
            && data.Protocol?.Contains("m3u8", StringComparison.OrdinalIgnoreCase) == true;

    private static bool IsSegmentedExternalFormat(MediaFormat format)
        => TryGetExternalData(format, out ExternalMediaFormatData data)
            && (data.Fragments.Count > 1
                || data.Protocol?.Contains("m3u8", StringComparison.OrdinalIgnoreCase) == true
                || data.Protocol?.Contains("dash", StringComparison.OrdinalIgnoreCase) == true);

    private static bool TryGetExternalData(MediaFormat format, out ExternalMediaFormatData data)
    {
        data = null!;
        if (string.IsNullOrWhiteSpace(format.ProviderData))
        {
            return false;
        }

        try
        {
            ExternalMediaFormatData? parsed = JsonSerializer.Deserialize<ExternalMediaFormatData>(format.ProviderData);
            if (parsed is null)
            {
                return false;
            }

            data = parsed;
            return true;
        }
        catch (JsonException)
        {
            return false;
        }
    }

    private static long? RemainingLimit(long? maximumBytes, long consumedBytes)
    {
        if (maximumBytes is not long limit)
        {
            return null;
        }

        long remaining = limit - consumedBytes;
        if (remaining <= 0)
        {
            throw new InvalidDataException($"Media capture exceeded the configured {limit} byte limit.");
        }

        return remaining;
    }

    private static string CreateFinalizationPath(string destinationPath)
    {
        string directory = Path.GetDirectoryName(destinationPath)!;
        string extension = Path.GetExtension(destinationPath);
        if (extension.Length == 0)
        {
            extension = ".mkv";
        }

        string fileName = Path.GetFileNameWithoutExtension(destinationPath);
        return Path.Combine(directory, $".{fileName}.xdm-finalizing{extension}");
    }

    private static string CreateWorkspace(
        string destinationPath,
        MediaDownloadRequest request,
        IReadOnlyList<MediaFormat> mainFormats,
        IReadOnlyList<MediaFormat> subtitles)
    {
        string key = FragmentIdentity.ShortHash(
            "media-workspace-v2",
            request.Source.AbsoluteUri,
            destinationPath,
            request.VideoFormatId,
            request.AudioFormatId,
            string.Join("\n", request.SubtitleIds.OrderBy(static id => id, StringComparer.Ordinal)),
            string.Join("\n", mainFormats.Concat(subtitles).Select(FormatIdentity)))[..16].ToLowerInvariant();
        return Path.Combine(Path.GetDirectoryName(destinationPath)!, ".xdm-media", key);
    }

    private static string FormatIdentity(MediaFormat format)
        => string.Join('|', format.Id, format.ManifestUri.AbsoluteUri, format.ProviderData ?? string.Empty);

    private static FileStream AcquireWorkspaceLock(string workspace)
    {
        try
        {
            return new FileStream(
                Path.Combine(workspace, ".workspace.lock"),
                FileMode.OpenOrCreate,
                FileAccess.ReadWrite,
                FileShare.None);
        }
        catch (IOException exception)
        {
            throw new InvalidOperationException("This media workspace is already being used by another capture.", exception);
        }
        catch (UnauthorizedAccessException exception)
        {
            throw new InvalidOperationException("This media workspace could not be locked for exclusive capture.", exception);
        }
    }

    private static string CreateSubtitleDestination(
        string destinationPath,
        MediaFormat subtitle,
        HashSet<string> reservedPaths)
    {
        string directory = Path.GetDirectoryName(destinationPath)!;
        string basename = Path.GetFileNameWithoutExtension(destinationPath);
        string label = FragmentIdentity.SanitizeFileComponent(subtitle.Language ?? subtitle.Name ?? subtitle.Id, "subtitle");
        string extension = ResolveSubtitleExtension(subtitle);
        string candidate = Path.Combine(directory, $"{basename}.{label}{extension}");
        int duplicate = 2;
        while (!reservedPaths.Add(candidate))
        {
            candidate = Path.Combine(directory, $"{basename}.{label}.{duplicate++}{extension}");
        }

        return candidate;
    }

    private static string ResolveSubtitleExtension(MediaFormat subtitle)
    {
        string?[] candidates =
        [
            subtitle.Container,
            Path.GetExtension(subtitle.ManifestUri.AbsolutePath).TrimStart('.')
        ];
        foreach (string? candidate in candidates)
        {
            string normalized = (candidate ?? string.Empty).Trim().TrimStart('.').ToLowerInvariant();
            switch (normalized)
            {
                case "vtt":
                case "webvtt":
                    return ".vtt";
                case "srt":
                case "subrip":
                    return ".srt";
                case "ttml":
                case "dfxp":
                case "xml":
                    return ".ttml";
                case "ass":
                case "ssa":
                    return ".ass";
            }
        }

        return ".vtt";
    }

    private static void ValidateRequest(MediaDownloadRequest request)
    {
        if (!request.Source.IsAbsoluteUri || request.Source.Scheme is not ("http" or "https"))
        {
            throw new ArgumentException("Media downloads require an absolute HTTP or HTTPS URL.", nameof(request));
        }

        if (string.IsNullOrWhiteSpace(request.DestinationPath))
        {
            throw new ArgumentException("A media destination path is required.", nameof(request));
        }

        if (request.LiveDuration is TimeSpan duration
            && (duration < TimeSpan.FromSeconds(5) || duration > TimeSpan.FromDays(7)))
        {
            throw new ArgumentOutOfRangeException(nameof(request), "Live capture duration must be between 5 seconds and 7 days.");
        }

        if (request.MaximumBytes is <= 0 or > 10L * 1024 * 1024 * 1024 * 1024)
        {
            throw new ArgumentOutOfRangeException(nameof(request), "Media size limit must be between 1 byte and 10 TiB.");
        }
    }

    private static void TryDeleteFile(string path)
    {
        try
        {
            if (File.Exists(path))
            {
                File.Delete(path);
            }
        }
        catch (IOException)
        {
        }
        catch (UnauthorizedAccessException)
        {
        }
    }

    private static void TryDeleteDirectory(string path)
    {
        try
        {
            if (Directory.Exists(path))
            {
                Directory.Delete(path, recursive: true);
            }
        }
        catch (IOException)
        {
        }
        catch (UnauthorizedAccessException)
        {
        }
    }

    private sealed record PendingSubtitle(string SourcePath, string DestinationPath);
}
