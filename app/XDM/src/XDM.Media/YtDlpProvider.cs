using System.ComponentModel;
using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace XDM.Media;

public sealed class YtDlpProvider : IYtDlpProvider
{
    private const int CatalogOutputLimitBytes = 16 * 1024 * 1024;
    private const int DiagnosticLimitCharacters = 2048;
    private readonly IExternalToolRunner _runner;
    private readonly IYtDlpNetworkPolicyProvider _networkPolicyProvider;
    private readonly string? _configuredExecutablePath;

    public YtDlpProvider(IExternalToolRunner runner)
        : this(runner, StaticYtDlpNetworkPolicyProvider.SystemDefault, null)
    {
    }

    public YtDlpProvider(IExternalToolRunner runner, IYtDlpNetworkPolicyProvider networkPolicyProvider)
        : this(runner, networkPolicyProvider, null)
    {
    }

    internal YtDlpProvider(IExternalToolRunner runner, string? executablePath)
        : this(runner, StaticYtDlpNetworkPolicyProvider.SystemDefault, executablePath)
    {
    }

    internal YtDlpProvider(
        IExternalToolRunner runner,
        IYtDlpNetworkPolicyProvider networkPolicyProvider,
        string? executablePath)
    {
        _runner = runner;
        _networkPolicyProvider = networkPolicyProvider;
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
        catch (Exception exception) when (IsToolBoundaryException(exception))
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

        string? metadataConfigPath = null;
        try
        {
            metadataConfigPath = await CreateMetadataConfigAsync(
                metadata,
                _networkPolicyProvider.Current,
                cancellationToken).ConfigureAwait(false);
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
            if (!result.Succeeded)
            {
                return CreateFailureCatalog(source, result.StandardError, "yt-dlp could not extract this URL");
            }

            if (string.IsNullOrWhiteSpace(result.StandardOutput))
            {
                return CreateFailureCatalog(source, result.StandardError, "yt-dlp returned no catalog output");
            }

            return ParseCatalog(source, result.StandardOutput);
        }
        catch (Exception exception) when (!cancellationToken.IsCancellationRequested
            && (IsToolBoundaryException(exception)
                || exception is JsonException
                || exception is InvalidDataException))
        {
            return CreateFailureCatalog(source, exception.Message, "yt-dlp discovery failed");
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
        if (!hasVideo && !hasAudio)
        {
            return null;
        }

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
            foreach (JsonElement fragment in fragmentElements.EnumerateArray())
            {
                string? fragmentUrl = GetString(fragment, "url");
                if (Uri.TryCreate(fragmentUrl, UriKind.Absolute, out Uri? fragmentUri)
                    && fragmentUri.Scheme is "http" or "https")
                {
                    string fragmentId = StableId(id, fragmentUri.AbsoluteUri, fragments.Count.ToString(CultureInfo.InvariantCulture));
                    fragments.Add(new ExternalMediaFragment($"fragment-{fragmentId}", fragmentUri));
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
            NormalizeContainer(GetString(format, "ext"), GetString(format, "container"), videoCodec, audioCodec),
            string.Join(",", new[] { videoCodec, audioCodec }.Where(static codec => !string.IsNullOrWhiteSpace(codec) && !string.Equals(codec, "none", StringComparison.OrdinalIgnoreCase))),
            ResolveBandwidth(format, kind),
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
        YtDlpNetworkPolicy networkPolicy,
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

        if (networkPolicy.ForceDirect)
        {
            lines.Add("--proxy \"\"");
        }
        else if (IsSafeConfigValue(networkPolicy.ProxyUri))
        {
            lines.Add($"--proxy {EscapeConfigValue(networkPolicy.ProxyUri!)}");
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
        bool created = false;
        try
        {
            await File.WriteAllLinesAsync(path, lines, cancellationToken).ConfigureAwait(false);
            created = true;
            if (!OperatingSystem.IsWindows())
            {
                File.SetUnixFileMode(path, UnixFileMode.UserRead | UnixFileMode.UserWrite);
            }

            return path;
        }
        catch
        {
            if (created || File.Exists(path))
            {
                TryDelete(path);
            }

            throw;
        }
    }

    private static void AddSubtitleFormats(JsonElement root, string propertyName, List<MediaFormat> formats)
    {
        if (!root.TryGetProperty(propertyName, out JsonElement subtitles)
            || subtitles.ValueKind != JsonValueKind.Object)
        {
            return;
        }

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

                string ext = GetString(subtitle, "ext") ?? "vtt";
                string? name = GetString(subtitle, "name") ?? GetString(subtitle, "format_id") ?? language.Name;
                string id = $"subtitle-{language.Name}-{StableId(language.Name, ext, name, uri.AbsoluteUri)}";
                formats.Add(new MediaFormat(
                    id,
                    MediaStreamKind.Subtitle,
                    uri,
                    ext,
                    null,
                    null,
                    null,
                    null,
                    null,
                    language.Name,
                    name,
                    false,
                    false,
                    JsonSerializer.Serialize(new ExternalMediaFormatData(uri.AbsoluteUri, "https", id, []))));
            }
        }
    }

    private static long? ResolveBandwidth(JsonElement format, MediaStreamKind kind)
    {
        long? bitrateKbps = GetLong(format, "tbr")
            ?? (kind == MediaStreamKind.Audio ? GetLong(format, "abr") : null)
            ?? (kind == MediaStreamKind.Video ? GetLong(format, "vbr") : null)
            ?? GetLong(format, "abr")
            ?? GetLong(format, "vbr");
        return bitrateKbps is long kbps and > 0 ? kbps * 1000 : null;
    }

    private static string? NormalizeContainer(string? ext, string? container, string? videoCodec, string? audioCodec)
    {
        string? value = !string.IsNullOrWhiteSpace(ext) ? ext : container;
        if (!string.IsNullOrWhiteSpace(value))
        {
            return value.Trim().TrimStart('.').ToLowerInvariant();
        }

        if (audioCodec?.Contains("opus", StringComparison.OrdinalIgnoreCase) == true
            || videoCodec?.Contains("vp9", StringComparison.OrdinalIgnoreCase) == true)
        {
            return "webm";
        }

        return null;
    }

    private static MediaCatalog CreateFailureCatalog(Uri source, string? diagnostic, string prefix)
    {
        string message = SanitizeDiagnostic(diagnostic);
        string description = string.IsNullOrWhiteSpace(message) ? prefix : $"{prefix}: {message}";
        return new MediaCatalog(source, MediaKind.Unknown, source.Host, false, [], description, "yt-dlp");
    }

    private static string SanitizeDiagnostic(string? value)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            return string.Empty;
        }

        string collapsed = value
            .Replace("\r", " ", StringComparison.Ordinal)
            .Replace("\n", " ", StringComparison.Ordinal)
            .Replace("\0", string.Empty, StringComparison.Ordinal)
            .Trim();
        return collapsed.Length > DiagnosticLimitCharacters
            ? collapsed[..DiagnosticLimitCharacters]
            : collapsed;
    }

    private static bool IsToolBoundaryException(Exception exception)
        => exception is IOException
            or InvalidOperationException
            or UnauthorizedAccessException
            or Win32Exception
            or TimeoutException
            or NotSupportedException;

    private static bool IsSafeConfigValue(string? value)
        => !string.IsNullOrWhiteSpace(value)
            && value.Length <= 8192
            && !value.Contains('\r')
            && !value.Contains('\n')
            && !value.Contains('\0');

    private static string EscapeConfigValue(string value)
        => "\"" + value
            .Replace("\\", "\\\\", StringComparison.Ordinal)
            .Replace("\"", "\\\"", StringComparison.Ordinal)
            + "\"";

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

    private static string StableId(params string?[] components)
    {
        string material = string.Join("\u001f", components.Where(static component => !string.IsNullOrWhiteSpace(component)));
        return Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(material))).ToLowerInvariant()[..16];
    }
}
