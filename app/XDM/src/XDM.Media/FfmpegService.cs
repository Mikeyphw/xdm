namespace XDM.Media;

public sealed class FfmpegService : IFfmpegService
{
    private const int OutputLimitBytes = 1024 * 1024;
    private readonly IExternalToolRunner _runner;
    private readonly string? _configuredExecutablePath;

    public FfmpegService(IExternalToolRunner runner)
        : this(runner, null)
    {
    }

    internal FfmpegService(IExternalToolRunner runner, string? executablePath)
    {
        _runner = runner;
        _configuredExecutablePath = executablePath;
    }

    public async Task<ExternalToolHealth> GetHealthAsync(CancellationToken cancellationToken = default)
    {
        string? path = _configuredExecutablePath ?? ExternalToolLocator.Find("ffmpeg");
        if (path is null)
        {
            return new ExternalToolHealth("FFmpeg", false, null, null, "FFmpeg was not found beside XDM or on PATH.");
        }

        try
        {
            ExternalToolResult result = await _runner.RunAsync(
                path,
                ["-hide_banner", "-version"],
                TimeSpan.FromSeconds(10),
                OutputLimitBytes,
                cancellationToken).ConfigureAwait(false);
            string? version = result.StandardOutput
                .Split('\n', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
                .FirstOrDefault();
            return result.Succeeded
                ? new ExternalToolHealth("FFmpeg", true, path, version, "FFmpeg is available for stream muxing.")
                : new ExternalToolHealth("FFmpeg", false, path, version, "FFmpeg was found but its health check failed.");
        }
        catch (IOException exception)
        {
            return new ExternalToolHealth("FFmpeg", false, path, null, exception.Message);
        }
        catch (InvalidOperationException exception)
        {
            return new ExternalToolHealth("FFmpeg", false, path, null, exception.Message);
        }
        catch (UnauthorizedAccessException exception)
        {
            return new ExternalToolHealth("FFmpeg", false, path, null, exception.Message);
        }
        catch (System.ComponentModel.Win32Exception exception)
        {
            return new ExternalToolHealth("FFmpeg", false, path, null, exception.Message);
        }
        catch (TimeoutException exception)
        {
            return new ExternalToolHealth("FFmpeg", false, path, null, exception.Message);
        }
    }


    public async Task<FfmpegCapabilities> GetCapabilitiesAsync(CancellationToken cancellationToken = default)
    {
        ExternalToolHealth health = await GetHealthAsync(cancellationToken).ConfigureAwait(false);
        if (!health.IsAvailable || string.IsNullOrWhiteSpace(health.ExecutablePath))
        {
            return new FfmpegCapabilities(health, false, false, false, false, false, false);
        }

        try
        {
            ExternalToolResult result = await _runner.RunAsync(
                health.ExecutablePath,
                ["-hide_banner", "-encoders"],
                TimeSpan.FromSeconds(15),
                4 * 1024 * 1024,
                cancellationToken).ConfigureAwait(false);
            return ParseCapabilities(health, result.StandardOutput);
        }
        catch (IOException)
        {
            return new FfmpegCapabilities(health, false, false, false, false, false, false);
        }
        catch (InvalidOperationException)
        {
            return new FfmpegCapabilities(health, false, false, false, false, false, false);
        }
        catch (UnauthorizedAccessException)
        {
            return new FfmpegCapabilities(health, false, false, false, false, false, false);
        }
        catch (System.ComponentModel.Win32Exception)
        {
            return new FfmpegCapabilities(health, false, false, false, false, false, false);
        }
        catch (TimeoutException)
        {
            return new FfmpegCapabilities(health, false, false, false, false, false, false);
        }
    }

    internal static FfmpegCapabilities ParseCapabilities(ExternalToolHealth health, string output)
    {
        string normalized = output.ToLowerInvariant();
        return new FfmpegCapabilities(
            health,
            normalized.Contains("libx264", StringComparison.Ordinal) || normalized.Contains(" h264 ", StringComparison.Ordinal),
            normalized.Contains("libx265", StringComparison.Ordinal) || normalized.Contains(" hevc ", StringComparison.Ordinal),
            normalized.Contains("libaom-av1", StringComparison.Ordinal) || normalized.Contains(" av1 ", StringComparison.Ordinal),
            normalized.Contains(" aac ", StringComparison.Ordinal),
            normalized.Contains("libmp3lame", StringComparison.Ordinal) || normalized.Contains(" mp3 ", StringComparison.Ordinal),
            normalized.Contains("libopus", StringComparison.Ordinal) || normalized.Contains(" opus ", StringComparison.Ordinal));
    }

    public async Task MuxAsync(
        IReadOnlyList<string> inputPaths,
        string destinationPath,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(inputPaths);
        ArgumentException.ThrowIfNullOrWhiteSpace(destinationPath);
        if (inputPaths.Count == 0)
        {
            throw new ArgumentException("At least one media input is required.", nameof(inputPaths));
        }

        string? path = _configuredExecutablePath ?? ExternalToolLocator.Find("ffmpeg");
        if (path is null)
        {
            throw new InvalidOperationException("FFmpeg is required to combine the selected media streams.");
        }
        string fullDestination = Path.GetFullPath(destinationPath);
        Directory.CreateDirectory(Path.GetDirectoryName(fullDestination)!);
        List<string> arguments = ["-hide_banner", "-nostdin", "-y"];
        foreach (string inputPath in inputPaths)
        {
            arguments.Add("-i");
            arguments.Add(Path.GetFullPath(inputPath));
        }

        arguments.AddRange(["-map", "0:v?", "-map", "0:a?"]);
        for (int index = 1; index < inputPaths.Count; index++)
        {
            arguments.Add("-map");
            arguments.Add($"{index}:v?");
            arguments.Add("-map");
            arguments.Add($"{index}:a?");
            arguments.Add("-map");
            arguments.Add($"{index}:s?");
        }

        arguments.AddRange(["-c", "copy", "-movflags", "+faststart", fullDestination]);
        ExternalToolResult result = await _runner.RunAsync(
            path,
            arguments,
            TimeSpan.FromHours(6),
            8 * 1024 * 1024,
            cancellationToken).ConfigureAwait(false);
        if (!result.Succeeded)
        {
            string message = string.IsNullOrWhiteSpace(result.StandardError)
                ? $"FFmpeg exited with code {result.ExitCode}."
                : result.StandardError.Trim();
            throw new InvalidOperationException(message);
        }
    }
}
