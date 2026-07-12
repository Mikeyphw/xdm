using System.Globalization;
using System.Text.Json;
using System.Xml;
using System.Xml.Linq;
using XDM.Core.Localization;
using XDM.Core.Settings;

namespace XDM.Persistence;

public sealed class SettingsTransferService : ISettingsTransferService
{
    private const string EnvelopeFormat = "xdm-modern-settings";
    private const long MaximumImportBytes = 4L * 1024 * 1024;
    private static readonly char[] LegacyListSeparators = [',', ';', ' '];
    private static readonly char[] PropertySeparators = ['=', ':'];
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web)
    {
        WriteIndented = true
    };

    public async Task ExportAsync(
        string path,
        ApplicationSettings settings,
        bool includeSecrets,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(path);
        ArgumentNullException.ThrowIfNull(settings);
        ApplicationSettings normalized = settings.Normalize();
        if (!includeSecrets)
        {
            ProxySettings proxy = normalized.Network!.Proxy!;
            normalized = normalized with
            {
                Network = normalized.Network with
                {
                    Proxy = proxy with { Password = null }
                },
                Credentials = normalized.Credentials?
                    .Select(static credential => credential with { Password = string.Empty })
                    .ToArray()
            };
        }

        string fullPath = Path.GetFullPath(path);
        string? directory = Path.GetDirectoryName(fullPath);
        if (!string.IsNullOrWhiteSpace(directory))
        {
            Directory.CreateDirectory(directory);
        }

        SettingsEnvelope envelope = new(EnvelopeFormat, 1, DateTimeOffset.UtcNow, includeSecrets, normalized);
        string temporaryPath = $"{fullPath}.tmp";
        await using (FileStream stream = new(
            temporaryPath,
            FileMode.Create,
            FileAccess.Write,
            FileShare.None,
            16 * 1024,
            FileOptions.Asynchronous | FileOptions.WriteThrough))
        {
            await JsonSerializer.SerializeAsync(stream, envelope, JsonOptions, cancellationToken).ConfigureAwait(false);
            await stream.FlushAsync(cancellationToken).ConfigureAwait(false);
        }

        File.Move(temporaryPath, fullPath, overwrite: true);
    }

    public async Task<SettingsImportResult> ImportAsync(
        string path,
        ApplicationSettings baseline,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(path);
        ArgumentNullException.ThrowIfNull(baseline);
        string sourcePath = ResolveSourcePath(path);
        FileInfo sourceInfo = new(sourcePath);
        if (sourceInfo.Length > MaximumImportBytes)
        {
            throw new InvalidDataException("The settings source exceeds the 4 MiB import limit.");
        }
        string extension = Path.GetExtension(sourcePath);
        if (extension.Equals(".json", StringComparison.OrdinalIgnoreCase))
        {
            string json = await File.ReadAllTextAsync(sourcePath, cancellationToken).ConfigureAwait(false);
            return ImportJson(json, baseline);
        }

        Dictionary<string, string> values = extension.Equals(".xml", StringComparison.OrdinalIgnoreCase)
            ? ParseXml(await File.ReadAllTextAsync(sourcePath, cancellationToken).ConfigureAwait(false))
            : ParseProperties(await File.ReadAllLinesAsync(sourcePath, cancellationToken).ConfigureAwait(false));
        return ImportLegacy(values, baseline, Path.GetFileName(sourcePath));
    }

    private static SettingsImportResult ImportJson(string json, ApplicationSettings baseline)
    {
        using JsonDocument document = JsonDocument.Parse(json, new JsonDocumentOptions
        {
            AllowTrailingCommas = true,
            CommentHandling = JsonCommentHandling.Skip,
            MaxDepth = 64
        });
        if (document.RootElement.ValueKind != JsonValueKind.Object)
        {
            throw new InvalidDataException("The settings JSON root must be an object.");
        }

        if (document.RootElement.TryGetProperty("format", out JsonElement format)
            && string.Equals(format.GetString(), EnvelopeFormat, StringComparison.Ordinal))
        {
            int version = document.RootElement.TryGetProperty("version", out JsonElement versionElement)
                && versionElement.TryGetInt32(out int parsedVersion)
                    ? parsedVersion
                    : 0;
            if (version is < 1 or > 1)
            {
                throw new InvalidDataException($"Unsupported settings export version: {version}.");
            }
            if (!document.RootElement.TryGetProperty("settings", out JsonElement settingsElement))
            {
                throw new InvalidDataException("The settings export does not contain settings.");
            }
            ApplicationSettings settings = settingsElement.Deserialize<ApplicationSettings>(JsonOptions)
                ?? throw new InvalidDataException("The settings export does not contain valid settings.");
            ApplicationSettings normalized = settings.Normalize();
            return CreateResult(normalized, "modern-json", new List<string>(), normalized);
        }

        try
        {
            ApplicationSettings? settings = JsonSerializer.Deserialize<ApplicationSettings>(json, JsonOptions);
            if (settings is not null && settings.Categories is not null && settings.Queues is not null)
            {
                ApplicationSettings normalized = settings.Normalize();
                return CreateResult(normalized, "modern-json", new List<string>(), normalized);
            }
        }
        catch (JsonException)
        {
        }

        Dictionary<string, string> flattened = [];
        FlattenJson(document.RootElement, string.Empty, flattened);
        return ImportLegacy(flattened, baseline, "legacy-json");
    }

    private static SettingsImportResult ImportLegacy(
        Dictionary<string, string> values,
        ApplicationSettings baseline,
        string sourceFormat)
    {
        ApplicationSettings current = baseline.Normalize();
        List<string> warnings = [];
        NetworkSettings network = current.Network!;
        ProxySettings proxy = network.Proxy!;
        DownloadBehaviorSettings behavior = current.DownloadBehavior!;
        LocalizationSettings localization = current.Localization ?? LocalizationSettings.Default;
        AccessibilitySettings accessibility = current.Accessibility ?? AccessibilitySettings.Default;

        string downloadDirectory = Get(values,
            "defaultDownloadDirectory", "default.folder", "download.folder", "downloadFolder", "saveTo")
            ?? current.DefaultDownloadDirectory;
        int maxConcurrent = GetInt(values, current.MaxConcurrentDownloads,
            "maxConcurrentDownloads", "max.concurrent.downloads", "maxDownloads", "simultaneousDownloads");
        long speedLimit = GetLong(values, current.DefaultSpeedLimitBytesPerSecond,
            "defaultSpeedLimitBytesPerSecond", "speed.limit.bytes", "speedLimit");
        bool clipboard = GetBool(values, current.ClipboardMonitoringEnabled,
            "clipboardMonitoringEnabled", "clipboard.monitor", "monitorClipboard");
        bool autoClipboard = GetBool(values, current.AutoAddClipboardLinks,
            "autoAddClipboardLinks", "clipboard.auto.add", "autoAddClipboard");

        network = network with
        {
            ConnectTimeoutSeconds = GetInt(values, network.ConnectTimeoutSeconds,
                "connectTimeoutSeconds", "connection.timeout", "connectTimeout"),
            RequestTimeoutSeconds = GetInt(values, network.RequestTimeoutSeconds,
                "requestTimeoutSeconds", "read.timeout", "requestTimeout"),
            MaximumRetryAttempts = GetInt(values, network.MaximumRetryAttempts,
                "maximumRetryAttempts", "max.retry", "maxRetry"),
            RetryBaseDelayMilliseconds = GetInt(values, network.RetryBaseDelayMilliseconds,
                "retryBaseDelayMilliseconds", "retry.delay", "retryDelay"),
            DefaultConnectionCount = GetInt(values, network.DefaultConnectionCount,
                "defaultConnectionCount", "connections", "segmentCount"),
            MaximumConnectionCount = GetInt(values, network.MaximumConnectionCount,
                "maximumConnectionCount", "max.connections", "maxConnections"),
            MinimumSegmentedSizeBytes = GetLong(values, network.MinimumSegmentedSizeBytes,
                "minimumSegmentedSizeBytes", "segment.min.size", "minSegmentedSize")
        };

        string? proxyMode = Get(values, "proxyMode", "proxy.mode", "network.proxy.type");
        proxy = proxy with
        {
            Mode = ParseProxyMode(proxyMode, proxy.Mode),
            Host = Get(values, "proxyHost", "proxy.host", "http.proxyHost") ?? proxy.Host,
            Port = GetInt(values, proxy.Port, "proxyPort", "proxy.port", "http.proxyPort"),
            Username = Get(values, "proxyUsername", "proxy.user", "http.proxyUser") ?? proxy.Username,
            Password = Get(values, "proxyPassword", "proxy.password", "http.proxyPassword") ?? proxy.Password,
            BypassLocal = GetBool(values, proxy.BypassLocal, "proxyBypassLocal", "proxy.bypass.local")
        };
        network = network with { Proxy = proxy };
        behavior = behavior with
        {
            DefaultDuplicateBehavior = Get(values,
                "defaultDuplicateBehavior", "duplicate.behavior", "fileExistsAction")
                ?? behavior.DefaultDuplicateBehavior,
            CreateDestinationDirectory = GetBool(values, behavior.CreateDestinationDirectory,
                "createDestinationDirectory", "create.folder"),
            AutoSelectCategory = GetBool(values, behavior.AutoSelectCategory,
                "autoSelectCategory", "category.auto.select")
        };

        string? legacyLanguage = Get(values, "language", "ui.language", "locale", "lang");
        if (!string.IsNullOrWhiteSpace(legacyLanguage))
        {
            localization = localization with
            {
                LanguageId = LegacyLanguageIndex.NormalizeIdentifier(legacyLanguage),
                UseSystemLanguage = false
            };
        }
        localization = localization with
        {
            UseSystemLanguage = GetBool(values, localization.UseSystemLanguage,
                "useSystemLanguage", "language.system", "locale.system")
        };
        accessibility = accessibility with
        {
            HighContrastEnabled = GetBool(values, accessibility.HighContrastEnabled,
                "highContrast", "accessibility.highContrast"),
            UiScalePercent = GetInt(values, accessibility.UiScalePercent,
                "uiScalePercent", "ui.scale", "accessibility.scale"),
            AnnounceStatusChanges = GetBool(values, accessibility.AnnounceStatusChanges,
                "announceStatusChanges", "accessibility.liveRegions")
        };

        DownloadCategoryDefinition[] categories = ParseCategories(values, downloadDirectory);
        DownloadQueueDefinition[] queues = ParseQueues(values);
        if (categories.Length == 0)
        {
            categories = current.Categories.ToArray();
            warnings.Add("No legacy category entries were found; existing categories were preserved.");
        }
        if (queues.Length == 0)
        {
            queues = current.Queues.ToArray();
            warnings.Add("No legacy queue entries were found; existing queues were preserved.");
        }

        ApplicationSettings imported = (current with
        {
            DefaultDownloadDirectory = downloadDirectory,
            MaxConcurrentDownloads = maxConcurrent,
            DefaultSpeedLimitBytesPerSecond = speedLimit,
            ClipboardMonitoringEnabled = clipboard,
            AutoAddClipboardLinks = autoClipboard,
            Categories = categories,
            Queues = queues,
            Network = network,
            DownloadBehavior = behavior,
            Localization = localization,
            Accessibility = accessibility
        }).Normalize();
        return CreateResult(imported, sourceFormat, warnings, imported);
    }

    private static SettingsImportResult CreateResult(
        ApplicationSettings settings,
        string format,
        List<string> warnings,
        ApplicationSettings counts)
        => new(
            settings,
            format,
            warnings,
            counts.Categories.Count,
            counts.Queues.Count,
            counts.Credentials?.Count ?? 0);

    private static DownloadCategoryDefinition[] ParseCategories(
        Dictionary<string, string> values,
        string fallbackDirectory)
    {
        Dictionary<string, Dictionary<string, string>> grouped = Group(values, "category.");
        return grouped
            .Select(pair => new DownloadCategoryDefinition(
                pair.Key,
                Value(pair.Value, "name") ?? pair.Key,
                Split(Value(pair.Value, "extensions") ?? Value(pair.Value, "types")),
                Value(pair.Value, "directory") ?? Value(pair.Value, "folder") ?? fallbackDirectory))
            .ToArray();
    }

    private static DownloadQueueDefinition[] ParseQueues(Dictionary<string, string> values)
    {
        Dictionary<string, Dictionary<string, string>> grouped = Group(values, "queue.");
        return grouped
            .Select(pair => new DownloadQueueDefinition(
                pair.Key,
                Value(pair.Value, "name") ?? pair.Key,
                ParseInt(Value(pair.Value, "maxConcurrent") ?? Value(pair.Value, "connections"), 1),
                ParseLong(Value(pair.Value, "speedLimitBytesPerSecond") ?? Value(pair.Value, "speedLimit"), 0)))
            .ToArray();
    }

    private static Dictionary<string, Dictionary<string, string>> Group(
        Dictionary<string, string> values,
        string prefix)
    {
        Dictionary<string, Dictionary<string, string>> grouped = new(StringComparer.OrdinalIgnoreCase);
        foreach ((string key, string value) in values)
        {
            if (!key.StartsWith(prefix, StringComparison.OrdinalIgnoreCase))
            {
                continue;
            }

            string remainder = key[prefix.Length..];
            int separator = remainder.IndexOf('.');
            if (separator <= 0 || separator == remainder.Length - 1)
            {
                continue;
            }

            string id = remainder[..separator];
            string property = remainder[(separator + 1)..];
            if (!grouped.TryGetValue(id, out Dictionary<string, string>? group))
            {
                group = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
                grouped[id] = group;
            }
            group[property] = value;
        }
        return grouped;
    }

    private static string ResolveSourcePath(string path)
    {
        string fullPath = Path.GetFullPath(path);
        if (File.Exists(fullPath))
        {
            return fullPath;
        }
        if (!Directory.Exists(fullPath))
        {
            throw new FileNotFoundException("The settings source does not exist.", fullPath);
        }

        string[] candidates =
        [
            "settings.json", "config.json", "config.properties", "settings.properties",
            "xdm.properties", "config.xml", "settings.xml"
        ];
        foreach (string candidate in candidates)
        {
            string candidatePath = Path.Combine(fullPath, candidate);
            if (File.Exists(candidatePath))
            {
                return candidatePath;
            }
        }
        throw new FileNotFoundException("No supported XDM settings file was found in the selected directory.", fullPath);
    }

    private static Dictionary<string, string> ParseProperties(string[] lines)
    {
        Dictionary<string, string> values = new(StringComparer.OrdinalIgnoreCase);
        foreach (string rawLine in lines)
        {
            string line = rawLine.Trim();
            if (line.Length == 0 || line.StartsWith('#') || line.StartsWith('!'))
            {
                continue;
            }
            int separator = line.IndexOfAny(PropertySeparators);
            if (separator <= 0)
            {
                continue;
            }
            values[line[..separator].Trim()] = line[(separator + 1)..].Trim();
        }
        return values;
    }

    private static Dictionary<string, string> ParseXml(string xml)
    {
        XmlReaderSettings settings = new()
        {
            DtdProcessing = DtdProcessing.Prohibit,
            XmlResolver = null,
            MaxCharactersInDocument = MaximumImportBytes
        };
        using StringReader textReader = new(xml);
        using XmlReader reader = XmlReader.Create(textReader, settings);
        XDocument document = XDocument.Load(reader, LoadOptions.None);
        Dictionary<string, string> values = new(StringComparer.OrdinalIgnoreCase);
        foreach (XElement element in document.Descendants())
        {
            string? key = element.Attribute("key")?.Value
                ?? element.Attribute("name")?.Value;
            if (!string.IsNullOrWhiteSpace(key))
            {
                values[key] = element.Attribute("value")?.Value ?? element.Value.Trim();
            }
        }
        return values;
    }

    private static void FlattenJson(JsonElement element, string prefix, Dictionary<string, string> values)
    {
        if (element.ValueKind == JsonValueKind.Object)
        {
            foreach (JsonProperty property in element.EnumerateObject())
            {
                string key = prefix.Length == 0 ? property.Name : $"{prefix}.{property.Name}";
                FlattenJson(property.Value, key, values);
            }
            return;
        }
        if (element.ValueKind == JsonValueKind.Array)
        {
            int index = 0;
            foreach (JsonElement item in element.EnumerateArray())
            {
                FlattenJson(item, $"{prefix}.{index++}", values);
            }
            return;
        }
        values[prefix] = element.ToString();
    }

    private static string? Get(
        Dictionary<string, string> values,
        string key1,
        string? key2 = null,
        string? key3 = null,
        string? key4 = null,
        string? key5 = null)
    {
        string? value = Find(values, key1)
            ?? Find(values, key2)
            ?? Find(values, key3)
            ?? Find(values, key4)
            ?? Find(values, key5);
        return string.IsNullOrWhiteSpace(value) ? null : value.Trim();
    }

    private static int GetInt(
        Dictionary<string, string> values,
        int fallback,
        string key1,
        string? key2 = null,
        string? key3 = null,
        string? key4 = null)
        => ParseInt(Get(values, key1, key2, key3, key4), fallback);

    private static long GetLong(
        Dictionary<string, string> values,
        long fallback,
        string key1,
        string? key2 = null,
        string? key3 = null,
        string? key4 = null)
        => ParseLong(Get(values, key1, key2, key3, key4), fallback);

    private static bool GetBool(
        Dictionary<string, string> values,
        bool fallback,
        string key1,
        string? key2 = null,
        string? key3 = null,
        string? key4 = null)
    {
        string? value = Get(values, key1, key2, key3, key4);
        return bool.TryParse(value, out bool result)
            ? result
            : int.TryParse(value, NumberStyles.Integer, CultureInfo.InvariantCulture, out int numeric)
                ? numeric != 0
                : fallback;
    }

    private static string? Find(Dictionary<string, string> values, string? key)
        => key is not null && values.TryGetValue(key, out string? value) ? value : null;

    private static int ParseInt(string? value, int fallback)
        => int.TryParse(value, NumberStyles.Integer, CultureInfo.InvariantCulture, out int result) ? result : fallback;

    private static long ParseLong(string? value, long fallback)
        => long.TryParse(value, NumberStyles.Integer, CultureInfo.InvariantCulture, out long result) ? result : fallback;

    private static ProxyMode ParseProxyMode(string? value, ProxyMode fallback)
        => value?.Trim().ToLowerInvariant() switch
        {
            "system" or "auto" or "0" => ProxyMode.System,
            "none" or "direct" or "1" => ProxyMode.None,
            "manual" or "custom" or "2" => ProxyMode.Manual,
            _ => fallback
        };

    private static string[] Split(string? value)
        => string.IsNullOrWhiteSpace(value)
            ? []
            : value.Split(LegacyListSeparators, StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);

    private static string? Value(Dictionary<string, string> values, string key)
        => values.TryGetValue(key, out string? value) ? value : null;

    private sealed record SettingsEnvelope(
        string Format,
        int Version,
        DateTimeOffset ExportedAt,
        bool IncludesSecrets,
        ApplicationSettings Settings);
}
