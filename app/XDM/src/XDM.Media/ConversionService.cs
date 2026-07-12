namespace XDM.Media;

public sealed class ConversionService : IConversionService
{
    private const int HealthOutputLimitBytes = 1024 * 1024;
    private readonly IExternalToolRunner _externalRunner;
    private readonly IConversionProcessRunner _processRunner;
    private readonly IMediaInspectionService _inspectionService;
    private readonly string? _configuredFfmpegPath;
    private readonly string? _configuredFfprobePath;

    public ConversionService(IExternalToolRunner externalRunner)
        : this(
            externalRunner,
            new FfmpegConversionProcessRunner(),
            new FfprobeMediaInspectionService(externalRunner),
            null,
            null)
    {
    }

    internal ConversionService(
        IExternalToolRunner externalRunner,
        IConversionProcessRunner processRunner,
        IMediaInspectionService inspectionService,
        string? ffmpegPath,
        string? ffprobePath)
    {
        _externalRunner = externalRunner;
        _processRunner = processRunner;
        _inspectionService = inspectionService;
        _configuredFfmpegPath = ffmpegPath;
        _configuredFfprobePath = ffprobePath;
    }

    public IReadOnlyList<ConversionPreset> Presets => ConversionPresetCatalog.Presets;

    public async Task<ExternalToolHealth> GetHealthAsync(CancellationToken cancellationToken = default)
    {
        string? ffmpegPath = ResolveFfmpegPath();
        if (ffmpegPath is null)
        {
            return new ExternalToolHealth(
                "FFmpeg conversion",
                false,
                null,
                null,
                "FFmpeg was not found beside XDM or on PATH.");
        }

        string? ffprobePath = ResolveFfprobePath(ffmpegPath);
        if (ffprobePath is null)
        {
            return new ExternalToolHealth(
                "FFmpeg conversion",
                false,
                ffmpegPath,
                null,
                "FFmpeg is available, but FFprobe is missing. Both tools are required for validated conversion.");
        }

        try
        {
            ExternalToolResult ffmpeg = await _externalRunner.RunAsync(
                ffmpegPath,
                ["-hide_banner", "-version"],
                TimeSpan.FromSeconds(10),
                HealthOutputLimitBytes,
                cancellationToken).ConfigureAwait(false);
            ExternalToolResult ffprobe = await _externalRunner.RunAsync(
                ffprobePath,
                ["-hide_banner", "-version"],
                TimeSpan.FromSeconds(10),
                HealthOutputLimitBytes,
                cancellationToken).ConfigureAwait(false);
            string[] versionLines = ffmpeg.StandardOutput
                .Split('\n', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
            string? version = versionLines.Length > 0 ? versionLines[0] : null;
            return ffmpeg.Succeeded && ffprobe.Succeeded
                ? new ExternalToolHealth(
                    "FFmpeg conversion",
                    true,
                    ffmpegPath,
                    version,
                    "FFmpeg and FFprobe are available for validated conversion.")
                : new ExternalToolHealth(
                    "FFmpeg conversion",
                    false,
                    ffmpegPath,
                    version,
                    "FFmpeg or FFprobe failed its compatibility check.");
        }
        catch (IOException exception)
        {
            return Unhealthy(ffmpegPath, exception.Message);
        }
        catch (InvalidDataException exception)
        {
            return Unhealthy(ffmpegPath, exception.Message);
        }
        catch (InvalidOperationException exception)
        {
            return Unhealthy(ffmpegPath, exception.Message);
        }
        catch (UnauthorizedAccessException exception)
        {
            return Unhealthy(ffmpegPath, exception.Message);
        }
        catch (System.ComponentModel.Win32Exception exception)
        {
            return Unhealthy(ffmpegPath, exception.Message);
        }
        catch (TimeoutException exception)
        {
            return Unhealthy(ffmpegPath, exception.Message);
        }
    }

    public Task<MediaInspection> InspectAsync(
        string sourcePath,
        CancellationToken cancellationToken = default)
        => _inspectionService.InspectAsync(sourcePath, cancellationToken);

    public async Task<ConversionResult> ConvertAsync(
        ConversionRequest request,
        IProgress<ConversionProgress>? progress = null,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(request);
        ConversionPresetDefinition definition = ConversionPresetCatalog.GetDefinition(request.PresetId);
        string sourcePath = Path.GetFullPath(request.SourcePath);
        string destinationPath = Path.GetFullPath(request.DestinationPath);
        ValidatePaths(sourcePath, destinationPath, definition.Preset, request.OverwriteExisting);

        progress?.Report(new ConversionProgress(
            ConversionJobState.Inspecting,
            "Inspecting streams and validating the selected container."));
        MediaInspection inspection = await _inspectionService
            .InspectAsync(sourcePath, cancellationToken)
            .ConfigureAwait(false);
        ValidateCompatibility(inspection, definition);

        string? ffmpegPath = ResolveFfmpegPath();
        if (ffmpegPath is null)
        {
            throw new InvalidOperationException("FFmpeg was not found beside XDM or on PATH.");
        }

        Directory.CreateDirectory(Path.GetDirectoryName(destinationPath)!);
        string temporaryPath = CreateTemporaryPath(destinationPath);
        List<string> arguments =
        [
            "-hide_banner",
            "-nostdin",
            "-y",
            "-loglevel",
            "warning",
            "-i",
            sourcePath
        ];
        arguments.AddRange(definition.FfmpegArguments);
        arguments.AddRange(["-progress", "pipe:1", "-nostats", "-f", definition.OutputFormat, temporaryPath]);

        progress?.Report(new ConversionProgress(
            ConversionJobState.Converting,
            $"Starting {definition.Preset.Name}."));
        try
        {
            ConversionProcessResult processResult = await _processRunner.RunAsync(
                ffmpegPath,
                arguments,
                inspection.Duration,
                progress,
                cancellationToken).ConfigureAwait(false);
            if (processResult.ExitCode != 0)
            {
                string message = string.IsNullOrWhiteSpace(processResult.StandardError)
                    ? $"FFmpeg exited with code {processResult.ExitCode}."
                    : processResult.StandardError.Trim();
                throw new InvalidOperationException(message);
            }

            if (!File.Exists(temporaryPath))
            {
                throw new InvalidDataException("FFmpeg reported success but did not produce an output file.");
            }

            long outputBytes = new FileInfo(temporaryPath).Length;
            if (outputBytes <= 0)
            {
                throw new InvalidDataException("FFmpeg produced an empty output file.");
            }

            cancellationToken.ThrowIfCancellationRequested();
            progress?.Report(new ConversionProgress(
                ConversionJobState.Finalizing,
                "Atomically publishing the converted file.",
                1,
                inspection.Duration,
                outputBytes));
            File.Move(temporaryPath, destinationPath, request.OverwriteExisting);
            if (request.PreserveSourceTimestamp)
            {
                File.SetLastWriteTimeUtc(destinationPath, File.GetLastWriteTimeUtc(sourcePath));
            }

            return new ConversionResult(
                sourcePath,
                destinationPath,
                definition.Preset,
                outputBytes,
                processResult.Elapsed);
        }
        finally
        {
            TryDelete(temporaryPath);
        }
    }

    private static void ValidatePaths(
        string sourcePath,
        string destinationPath,
        ConversionPreset preset,
        bool overwriteExisting)
    {
        if (!File.Exists(sourcePath))
        {
            throw new FileNotFoundException("The conversion source file does not exist.", sourcePath);
        }

        StringComparison comparison = OperatingSystem.IsWindows()
            ? StringComparison.OrdinalIgnoreCase
            : StringComparison.Ordinal;
        if (string.Equals(sourcePath, destinationPath, comparison))
        {
            throw new ArgumentException("The conversion destination must differ from the source file.", nameof(destinationPath));
        }

        if (!string.Equals(Path.GetExtension(destinationPath), preset.FileExtension, StringComparison.OrdinalIgnoreCase))
        {
            throw new ArgumentException(
                $"The {preset.Name} preset requires a '{preset.FileExtension}' destination.",
                nameof(destinationPath));
        }

        if (File.Exists(destinationPath) && !overwriteExisting)
        {
            throw new IOException("The conversion destination already exists and overwrite is disabled.");
        }
    }

    private static void ValidateCompatibility(
        MediaInspection inspection,
        ConversionPresetDefinition definition)
    {
        if (!inspection.HasVideo && !inspection.HasAudio)
        {
            throw new InvalidDataException("No audio or video stream was found in the source file.");
        }

        switch (definition.Preset.Kind)
        {
            case ConversionKind.AudioExtraction when !inspection.HasAudio:
                throw new InvalidDataException("The selected source does not contain an audio stream.");
            case ConversionKind.VideoTranscode when !inspection.HasVideo:
                throw new InvalidDataException("The selected MP4 preset requires a video stream.");
            case ConversionKind.Remux:
                ValidateRemuxCompatibility(inspection, definition);
                break;
        }
    }

    private static void ValidateRemuxCompatibility(
        MediaInspection inspection,
        ConversionPresetDefinition definition)
    {
        if (inspection.HasVideo
            && (inspection.VideoCodec is null
                || definition.CompatibleVideoCodecs?.Contains(inspection.VideoCodec) != true))
        {
            throw new InvalidDataException(
                $"Video codec '{inspection.VideoCodec ?? "unknown"}' cannot be copied safely into MP4. Choose an H.264 transcode preset.");
        }

        if (inspection.HasAudio
            && (inspection.AudioCodec is null
                || definition.CompatibleAudioCodecs?.Contains(inspection.AudioCodec) != true))
        {
            throw new InvalidDataException(
                $"Audio codec '{inspection.AudioCodec ?? "unknown"}' cannot be copied safely into MP4. Choose a transcode preset.");
        }
    }

    private string? ResolveFfmpegPath()
        => _configuredFfmpegPath ?? ExternalToolLocator.Find("ffmpeg");

    private string? ResolveFfprobePath(string? ffmpegPath = null)
    {
        if (_configuredFfprobePath is not null)
        {
            return _configuredFfprobePath;
        }

        string? resolvedFfmpeg = ffmpegPath ?? ResolveFfmpegPath();
        if (resolvedFfmpeg is not null)
        {
            string executableName = OperatingSystem.IsWindows() ? "ffprobe.exe" : "ffprobe";
            string adjacent = Path.Combine(Path.GetDirectoryName(resolvedFfmpeg)!, executableName);
            if (File.Exists(adjacent))
            {
                return Path.GetFullPath(adjacent);
            }
        }

        return ExternalToolLocator.Find("ffprobe");
    }

    private static string CreateTemporaryPath(string destinationPath)
    {
        string directory = Path.GetDirectoryName(destinationPath)!;
        string fileName = Path.GetFileName(destinationPath);
        return Path.Combine(directory, $".{fileName}.{Guid.NewGuid():N}.xdm-converting");
    }

    private static void TryDelete(string path)
    {
        try
        {
            File.Delete(path);
        }
        catch (IOException)
        {
        }
        catch (UnauthorizedAccessException)
        {
        }
    }

    private static ExternalToolHealth Unhealthy(string path, string message)
        => new("FFmpeg conversion", false, path, null, message);
}
