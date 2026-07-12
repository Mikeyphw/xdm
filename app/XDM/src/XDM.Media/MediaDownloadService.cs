using System.Diagnostics;
using System.Security.Cryptography;
using System.Text;
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
        string workspace = CreateWorkspace(destinationPath, request.Source);
        Directory.CreateDirectory(workspace);
        Stopwatch stopwatch = Stopwatch.StartNew();
        List<StreamDownloadResult> mainStreams = [];
        List<string> subtitlePaths = [];
        bool usedFfmpeg = false;
        bool completedSuccessfully = false;
        try
        {
            int formatIndex = 0;
            foreach (MediaFormat format in mainFormats)
            {
                progress?.Report(new MediaDownloadProgress(
                    "Preparing media stream",
                    formatIndex,
                    mainFormats.Count,
                    mainStreams.Sum(static stream => stream.DownloadedBytes),
                    $"Preparing {format.DisplayName}."));
                mainStreams.Add(await DownloadFormatAsync(
                    catalog.Kind,
                    format,
                    workspace,
                    metadata,
                    request.LiveDuration,
                    progress,
                    cancellationToken).ConfigureAwait(false));
                formatIndex++;
            }

            foreach (MediaFormat subtitle in subtitles)
            {
                StreamDownloadResult subtitleStream = await DownloadFormatAsync(
                    catalog.Kind,
                    subtitle,
                    workspace,
                    metadata,
                    request.LiveDuration,
                    progress,
                    cancellationToken).ConfigureAwait(false);
                string language = SanitizeFileComponent(subtitle.Language ?? subtitle.Name ?? subtitle.Id);
                string subtitlePath = Path.Combine(
                    Path.GetDirectoryName(destinationPath)!,
                    $"{Path.GetFileNameWithoutExtension(destinationPath)}.{language}.vtt");
                File.Move(subtitleStream.Path, subtitlePath, overwrite: true);
                subtitlePaths.Add(subtitlePath);
            }

            bool requiresMux = mainStreams.Count > 1
                || catalog.Kind is MediaKind.Hls or MediaKind.Dash
                || catalog.Kind == MediaKind.ExternalProvider
                    && mainFormats.Any(IsSegmentedExternalFormat);
            if (requiresMux)
            {
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
                await ffmpegService.MuxAsync(
                    mainStreams.Select(static stream => stream.Path).ToArray(),
                    temporaryDestination,
                    cancellationToken).ConfigureAwait(false);
                File.Move(temporaryDestination, destinationPath, overwrite: true);
                usedFfmpeg = true;
            }
            else
            {
                string temporaryDestination = CreateFinalizationPath(destinationPath);
                File.Copy(mainStreams[0].Path, temporaryDestination, overwrite: true);
                File.Move(temporaryDestination, destinationPath, overwrite: true);
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
                progress,
                cancellationToken).ConfigureAwait(false);
        }

        string formatDirectory = Path.Combine(workspace, SanitizeFileComponent(format.Id));
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

    private static string CreateWorkspace(string destinationPath, Uri source)
    {
        string key = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(source.AbsoluteUri)))[..16];
        return Path.Combine(Path.GetDirectoryName(destinationPath)!, ".xdm-media", key);
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
    }

    private static string SanitizeFileComponent(string value)
    {
        HashSet<char> invalid = new(Path.GetInvalidFileNameChars());
        string sanitized = new(value.Select(character => invalid.Contains(character) ? '_' : character).ToArray());
        return sanitized.Length == 0 ? "media" : sanitized;
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
}
