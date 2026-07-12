using System.Globalization;
using System.Text.Json;

namespace XDM.Media;

internal sealed class FfprobeMediaInspectionService : IMediaInspectionService
{
    private const int MaximumProbeOutputBytes = 4 * 1024 * 1024;
    private readonly IExternalToolRunner _runner;
    private readonly string? _configuredExecutablePath;

    public FfprobeMediaInspectionService(
        IExternalToolRunner runner,
        string? configuredExecutablePath = null)
    {
        _runner = runner;
        _configuredExecutablePath = configuredExecutablePath;
    }

    public async Task<MediaInspection> InspectAsync(
        string sourcePath,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(sourcePath);
        string fullSourcePath = Path.GetFullPath(sourcePath);
        if (!File.Exists(fullSourcePath))
        {
            throw new FileNotFoundException("The conversion source file does not exist.", fullSourcePath);
        }

        string? executablePath = _configuredExecutablePath ?? ExternalToolLocator.Find("ffprobe");
        if (executablePath is null)
        {
            throw new InvalidOperationException("FFprobe was not found beside XDM or on PATH. It is required for safe stream validation.");
        }

        ExternalToolResult result = await _runner.RunAsync(
            executablePath,
            [
                "-v", "error",
                "-show_entries", "format=duration,format_name:stream=codec_type,codec_name",
                "-of", "json",
                fullSourcePath
            ],
            TimeSpan.FromSeconds(30),
            MaximumProbeOutputBytes,
            cancellationToken).ConfigureAwait(false);
        if (!result.Succeeded)
        {
            string error = string.IsNullOrWhiteSpace(result.StandardError)
                ? $"FFprobe exited with code {result.ExitCode}."
                : result.StandardError.Trim();
            throw new InvalidDataException(error);
        }

        return Parse(result.StandardOutput);
    }

    internal static MediaInspection Parse(string json)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(json);
        using JsonDocument document = JsonDocument.Parse(json, new JsonDocumentOptions
        {
            AllowTrailingCommas = false,
            CommentHandling = JsonCommentHandling.Disallow,
            MaxDepth = 32
        });
        JsonElement root = document.RootElement;
        string? formatName = null;
        TimeSpan? duration = null;
        if (root.TryGetProperty("format", out JsonElement format))
        {
            formatName = GetString(format, "format_name");
            string? durationText = GetString(format, "duration");
            if (double.TryParse(durationText, NumberStyles.Float, CultureInfo.InvariantCulture, out double seconds)
                && double.IsFinite(seconds)
                && seconds >= 0)
            {
                duration = TimeSpan.FromSeconds(seconds);
            }
        }

        string? videoCodec = null;
        string? audioCodec = null;
        if (root.TryGetProperty("streams", out JsonElement streams)
            && streams.ValueKind == JsonValueKind.Array)
        {
            foreach (JsonElement stream in streams.EnumerateArray())
            {
                string? codecType = GetString(stream, "codec_type");
                string? codecName = GetString(stream, "codec_name");
                if (videoCodec is null && string.Equals(codecType, "video", StringComparison.Ordinal))
                {
                    videoCodec = codecName;
                }
                else if (audioCodec is null && string.Equals(codecType, "audio", StringComparison.Ordinal))
                {
                    audioCodec = codecName;
                }
            }
        }

        return new MediaInspection(
            duration,
            formatName,
            videoCodec,
            audioCodec,
            videoCodec is not null,
            audioCodec is not null);
    }

    private static string? GetString(JsonElement parent, string propertyName)
        => parent.TryGetProperty(propertyName, out JsonElement value)
            && value.ValueKind == JsonValueKind.String
                ? value.GetString()
                : null;
}
