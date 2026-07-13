using System.Globalization;
using System.Text.Json;

namespace XDM.Media;

public sealed class YtDlpProvider : IYtDlpProvider
{
    private const int CatalogOutputLimitBytes = 16 * 1024 * 1024;
    private readonly IExternalToolRunner _runner;
    private readonly string? _configuredExecutablePath;

    public YtDlpProvider(IExternalToolRunner runner)
        : this(runner, null)
    {
    }

    internal YtDlpProvider(IExternalToolRunner runner, string? executablePath)
    {
        _runner = runner;
        _configuredExecutablePath = executablePath;
    }

    public async Task<ExternalToolHealth> GetHealthAsync(CancellationToken cancellationToken = default)
    {
        string? path = FindExecutable();
        if (path is null)
        {
            return new ExternalToolHealth("yt-dlp", false, null, null, "yt-dlp was not found beside XDM or on PATH.");
        }

        try
        {
            ExternalToolResult result = await _runner.RunAsync(
                path,
                ["--version"],
                TimeSpan.FromSeconds(10),
                256 * 1024,
                cancellationToken).ConfigureAwait(false);
            string? version = result.StandardOutput.Trim().Split('\n').FirstOrDefault();
            return result.Succeeded
                ? new ExternalToolHealth("yt-dlp", true, path, version, "yt-dlp is available for supported media pages.")
                : new ExternalToolHealth("yt-dlp", false, path, version, "yt-dlp was found but its health check failed.");
        }
        catch (IOException exception)
        {
            return new ExternalToolHealth("yt-dlp", false, path, null, exception.Message);
        }
        catch (InvalidOperationException exception)
        {
            return new ExternalToolHealth("yt-dlp", false, path, null, exception.Message);
        }
        catch (UnauthorizedAccessException exception)
        {
            return new ExternalToolHealth("yt-dlp", false, path, null, exception.Message);
        }
        catch (System.ComponentModel.Win32Exception exception)
        {
            return new ExternalToolHealth("yt-dlp", false, path, null, exception.Message);
        }
        catch (TimeoutException exception)
        {
            return new ExternalToolHealth("yt-dlp", false, path, null, exception.Message);
        }
    }

    public async Task<MediaCatalog?> TryGetCatalogAsync(
        Uri source,
        MediaRequestMetadata metadata,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(source);
        ArgumentNullException.ThrowIfNull(metadata);
        string? path = FindExecutable();
        if (path is null)
        {
            return null;
        }

        string? metadataConfigPath = await CreateMetadataConfigAsync(metadata, cancellationToken).ConfigureAwait(false);
        try
        {
            List<string> arguments =
            [
                "--ignore-config",
                "--dump-single-json",
                "--no-playlist",
                "--no-warnings",
                "--skip-download"
            ];
            if (metadataConfigPath is not null)
            {
                arguments.Add("--config-locations");
                arguments.Add(metadataConfigPath);
            }

            arguments.Add("--");
            arguments.Add(source.AbsoluteUri);
            ExternalToolResult result = await _runner.RunAsync(
                path,
                arguments,
                TimeSpan.FromMinutes(2),
                CatalogOutputLimitBytes,
                cancellationToken).ConfigureAwait(false);
            if (!result.Succeeded || string.IsNullOrWhiteSpace(result.StandardOutput))
            {
                return null;
            }

            return ParseCatalog(source, result.StandardOutput);
        }
        finally
        {
            if (metadataConfigPath is not null)
            {
                TryDelete(metadataConfigPath);
            }
        }
    }

    internal static MediaCatalog ParseCatalog(Uri source, string json)
    {
        JsonDocumentOptions options = new()
        {
            AllowTrailingCommas = false,
            CommentHandling = JsonCommentHandling.Disallow,
            MaxDepth = 64
        };
        using JsonDocument document = JsonDocument.Parse(json, options);
        JsonElement root = document.RootElement;
        string title = GetString(root, "title") ?? source.Host;
        bool isLive = GetBoolean(root, "is_live") || string.Equals(GetString(root, "live_status"), "is_live", StringComparison.Ordinal);
        List<MediaFormat> formats = [];
        if (root.TryGetProperty("formats", out JsonElement formatElements)
            && formatElements.ValueKind == JsonValueKind.Array)
        {
            foreach (JsonElement format in formatElements.EnumerateArray())
            {
                MediaFormat? parsed = ParseFormat(format);
                if (parsed is not null)
                {
                    formats.Add(parsed);
                }
            }
        }

        AddSubtitleFormats(root, "subtitles", formats);

        if (formats.Count == 0)
        {
            MediaFormat? direct = ParseFormat(root);
            if (direct is not null)
            {
                formats.Add(direct);
            }
        }

        if (formats.Count == 0)
        {
            throw new InvalidDataException("yt-dlp did not return any usable HTTP media formats.");
        }

        double? durationSeconds = GetDouble(root, "duration");
        TimeSpan? duration = durationSeconds is > 0 and <= 604800
            ? TimeSpan.FromSeconds(durationSeconds.Value)
            : null;
        return new MediaCatalog(
            source,
            MediaKind.ExternalProvider,
            title,
            isLive,
            formats,
            $"yt-dlp returned {formats.Count} selectable format(s).",
            "yt-dlp",
            duration);
    }

    private static MediaFormat? ParseFormat(JsonElement format)
    {
        string? id = GetString(format, "format_id");
        string? urlText = GetString(format, "url");
        if (string.IsNullOrWhiteSpace(id)
            || !Uri.TryCreate(urlText, UriKind.Absolute, out Uri? url)
            || url.Scheme is not ("http" or "https"))
        {
            return null;
        }

        string? videoCodec = GetString(format, "vcodec");
        string? audioCodec = GetString(format, "acodec");
        bool hasVideo = !string.IsNullOrWhiteSpace(videoCodec) && !string.Equals(videoCodec, "none", StringComparison.OrdinalIgnoreCase);
        bool hasAudio = !string.IsNullOrWhiteSpace(audioCodec) && !string.Equals(audioCodec, "none", StringComparison.OrdinalIgnoreCase);
        MediaStreamKind kind = hasVideo && hasAudio
            ? MediaStreamKind.Muxed
            : hasVideo
                ? MediaStreamKind.Video
                : MediaStreamKind.Audio;
        string? protocol = GetString(format, "protocol");
        List<ExternalMediaFragment> fragments = [];
        if (format.TryGetProperty("fragments", out JsonElement fragmentElements)
            && fragmentElements.ValueKind == JsonValueKind.Array)
        {
            int fragmentIndex = 0;
            foreach (JsonElement fragment in fragmentElements.EnumerateArray())
            {
                string? fragmentUrl = GetString(fragment, "url");
                if (Uri.TryCreate(fragmentUrl, UriKind.Absolute, out Uri? fragmentUri)
                    && fragmentUri.Scheme is "http" or "https")
                {
                    fragments.Add(new ExternalMediaFragment($"fragment-{fragmentIndex++:D10}", fragmentUri));
                }
            }
        }

        string providerData = JsonSerializer.Serialize(new ExternalMediaFormatData(
            url.AbsoluteUri,
            protocol,
            id,
            fragments));
        return new MediaFormat(
            id,
            kind,
            url,
            GetString(format, "ext"),
            string.Join(",", new[] { videoCodec, audioCodec }.Where(static codec => !string.IsNullOrWhiteSpace(codec) && codec != "none")),
            GetLong(format, "tbr") is long tbr ? tbr * 1000 : null,
            GetInt(format, "width"),
            GetInt(format, "height"),
            GetDouble(format, "fps"),
            GetString(format, "language"),
            GetString(format, "format_note") ?? GetString(format, "format"),
            false,
            false,
            providerData);
    }

    private static async Task<string?> CreateMetadataConfigAsync(
        MediaRequestMetadata metadata,
        CancellationToken cancellationToken)
    {
        List<string> lines = [];
        if (IsSafeConfigValue(metadata.UserAgent))
        {
            lines.Add($"--user-agent {EscapeConfigValue(metadata.UserAgent!)}");
        }

        if (IsSafeConfigValue(metadata.Referer))
        {
            lines.Add($"--referer {EscapeConfigValue(metadata.Referer!)}");
        }

        if (IsSafeConfigValue(metadata.Cookie))
        {
            string cookieHeader = $"Cookie:{metadata.Cookie}";
            lines.Add($"--add-header {EscapeConfigValue(cookieHeader)}");
        }

        if (metadata.Headers is not null)
        {
            foreach ((string name, string value) in metadata.Headers.OrderBy(static pair => pair.Key, StringComparer.OrdinalIgnoreCase))
            {
                if (name.Equals("Cookie", StringComparison.OrdinalIgnoreCase)
                    || name.Equals("Referer", StringComparison.OrdinalIgnoreCase)
                    || name.Equals("User-Agent", StringComparison.OrdinalIgnoreCase)
                    || name.Contains('\r')
                    || name.Contains('\n')
                    || value.Contains('\r')
                    || value.Contains('\n')
                    || name.Contains('\0')
                    || value.Contains('\0')
                    || name.Length > 64
                    || value.Length > 8192)
                {
                    continue;
                }

                string header = $"{name}:{value}";
                lines.Add($"--add-header {EscapeConfigValue(header)}");
            }
        }

        if (lines.Count == 0)
        {
            return null;
        }

        string directory = Path.Combine(Path.GetTempPath(), "xdm-media");
        Directory.CreateDirectory(directory);
        string path = Path.Combine(directory, $"yt-dlp-{Guid.NewGuid():N}.conf");
        await File.WriteAllLinesAsync(path, lines, cancellationToken).ConfigureAwait(false);
        if (!OperatingSystem.IsWindows())
        {
            File.SetUnixFileMode(path, UnixFileMode.UserRead | UnixFileMode.UserWrite);
        }

        return path;
    }

    private static void AddSubtitleFormats(JsonElement root, string propertyName, List<MediaFormat> formats)
    {
        if (!root.TryGetProperty(propertyName, out JsonElement subtitles)
            || subtitles.ValueKind != JsonValueKind.Object)
        {
            return;
        }

        int index = 0;
        foreach (JsonProperty language in subtitles.EnumerateObject())
        {
            if (language.Value.ValueKind != JsonValueKind.Array)
            {
                continue;
            }

            foreach (JsonElement subtitle in language.Value.EnumerateArray())
            {
                string? urlText = GetString(subtitle, "url");
                if (!Uri.TryCreate(urlText, UriKind.Absolute, out Uri? uri)
                    || uri.Scheme is not ("http" or "https"))
                {
                    continue;
                }

                string id = $"subtitle-{language.Name}-{index++}";
                formats.Add(new MediaFormat(
                    id,
                    MediaStreamKind.Subtitle,
                    uri,
                    GetString(subtitle, "ext") ?? "vtt",
                    null,
                    null,
                    null,
                    null,
                    null,
                    language.Name,
                    GetString(subtitle, "name") ?? language.Name,
                    false,
                    false,
                    JsonSerializer.Serialize(new ExternalMediaFormatData(uri.AbsoluteUri, "https", id, []))));
            }
        }
    }

    private static bool IsSafeConfigValue(string? value)
        => !string.IsNullOrWhiteSpace(value)
            && value.Length <= 8192
            && !value.Contains('\r')
            && !value.Contains('\n')
            && !value.Contains('\0');

    private static string EscapeConfigValue(string value)
        => $"\"{value.Replace("\\", "\\\\", StringComparison.Ordinal).Replace("\"", "\\\"", StringComparison.Ordinal)}\"";

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

    private string? FindExecutable()
        => _configuredExecutablePath
            ?? ExternalToolLocator.Find("yt-dlp")
            ?? ExternalToolLocator.Find("yt-dlp.exe");

    private static string? GetString(JsonElement element, string property)
        => element.TryGetProperty(property, out JsonElement value) && value.ValueKind == JsonValueKind.String
            ? value.GetString()
            : null;

    private static bool GetBoolean(JsonElement element, string property)
        => element.TryGetProperty(property, out JsonElement value)
            && value.ValueKind is JsonValueKind.True or JsonValueKind.False
            && value.GetBoolean();

    private static int? GetInt(JsonElement element, string property)
        => element.TryGetProperty(property, out JsonElement value)
            && value.TryGetInt32(out int parsed)
                ? parsed
                : null;

    private static long? GetLong(JsonElement element, string property)
    {
        if (!element.TryGetProperty(property, out JsonElement value))
        {
            return null;
        }

        if (value.TryGetInt64(out long integer))
        {
            return integer;
        }

        return value.TryGetDouble(out double number)
            ? Convert.ToInt64(number)
            : null;
    }

    private static double? GetDouble(JsonElement element, string property)
        => element.TryGetProperty(property, out JsonElement value) && value.TryGetDouble(out double parsed)
            ? parsed
            : null;
}
