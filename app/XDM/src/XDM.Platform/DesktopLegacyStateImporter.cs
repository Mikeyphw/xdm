using System.Text.Json;
using XDM.Core.Persistence;

namespace XDM.Platform;

/// <summary>
/// One-shot XGO-80 importer for pre-Go desktop JSON state. It reads legacy files only
/// before the durable Go authority snapshot exists and never writes them back.
/// </summary>
public sealed class DesktopLegacyStateImporter(DesktopGoProjectionClient go)
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web)
    {
        PropertyNamingPolicy = JsonNamingPolicy.SnakeCaseLower,
        DictionaryKeyPolicy = JsonNamingPolicy.SnakeCaseLower
    };

    private readonly DesktopGoProjectionClient _go = go ?? throw new ArgumentNullException(nameof(go));
    private readonly string _legacyDirectory = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "xdm-modern");

    public async Task ImportOnceAsync(CancellationToken cancellationToken = default)
    {
        string authorityPath = Path.Combine(_legacyDirectory, "go-desktop-state-v1.json");
        if (File.Exists(authorityPath))
        {
            return;
        }

        PersistedDownload[] downloads = await LoadDownloadsAsync(cancellationToken).ConfigureAwait(false);
        object[] projected = downloads.Select(static item => (object)new
        {
            id = item.Id,
            file_name = Path.GetFileName(item.DestinationPath),
            source_url = SafeProjectionUrl(item.Source),
            destination_path = item.DestinationPath,
            downloaded_bytes = Math.Max(0, item.DownloadedBytes),
            total_bytes = item.TotalBytes,
            bytes_per_second = 0d,
            state = item.State.ToString(),
            updated_at_epoch_ms = Math.Max(0, item.UpdatedAt.ToUnixTimeMilliseconds()),
            error_message = item.ErrorMessage,
            queue_id = string.IsNullOrWhiteSpace(item.QueueId) ? "default" : item.QueueId,
            category_id = item.CategoryId,
            queue_order = item.QueueOrder,
            connection_count = Math.Max(1, item.ConnectionCount),
            priority = item.Priority.ToString(),
            backend = item.Backend.ToString(),
            backend_task_id = string.IsNullOrWhiteSpace(item.BackendTaskId) ? null : item.BackendTaskId,
            backend_preference = item.BackendPreference.ToString(),
            allow_backend_fallback = item.AllowBackendFallback,
            archived = item.IsArchived
        }).ToArray();

        object[] checkpoints = downloads
            .Select(CreateCheckpointRecord)
            .Where(static item => item is not null)
            .Cast<object>()
            .ToArray();

        (object settings, object[] queues, object[] schedules) = await LoadSettingsSummaryAsync(cancellationToken).ConfigureAwait(false);
        object package = new
        {
            package_version = 1,
            downloads = projected,
            settings,
            checkpoints,
            queues,
            schedules,
            installations = new[]
            {
                new { id = "desktop-v1", payload = new { source = "xdm-modern-json", authority = "go" } }
            }
        };
        await _go.ImportLegacyStateAsync(JsonSerializer.SerializeToElement(package, JsonOptions), cancellationToken).ConfigureAwait(false);
    }

    private async Task<PersistedDownload[]> LoadDownloadsAsync(CancellationToken cancellationToken)
    {
        string path = Path.Combine(_legacyDirectory, "downloads.json");
        string backupPath = path + ".bak";
        if (!File.Exists(path))
        {
            return File.Exists(backupPath)
                ? await LoadDownloadsFileAsync(backupPath, cancellationToken).ConfigureAwait(false)
                : [];
        }

        try
        {
            return await LoadDownloadsFileAsync(path, cancellationToken).ConfigureAwait(false);
        }
        catch (Exception primary) when (primary is IOException or UnauthorizedAccessException or JsonException or InvalidDataException or NotSupportedException)
        {
            if (File.Exists(backupPath))
            {
                try
                {
                    return await LoadDownloadsFileAsync(backupPath, cancellationToken).ConfigureAwait(false);
                }
                catch (Exception backup) when (backup is IOException or UnauthorizedAccessException or JsonException or InvalidDataException or NotSupportedException)
                {
                    throw new InvalidDataException("Legacy desktop download history and its backup could not be read; Go cutover was not committed.", new AggregateException(primary, backup));
                }
            }

            throw new InvalidDataException("Legacy desktop download history could not be read; Go cutover was not committed.", primary);
        }
    }

    private static async Task<PersistedDownload[]> LoadDownloadsFileAsync(string path, CancellationToken cancellationToken)
    {
        await using FileStream stream = new(path, FileMode.Open, FileAccess.Read, FileShare.Read, 16 * 1024, FileOptions.Asynchronous | FileOptions.SequentialScan);
        using JsonDocument document = await JsonDocument.ParseAsync(stream, cancellationToken: cancellationToken).ConfigureAwait(false);
        JsonElement root = document.RootElement;
        JsonElement downloads = root.ValueKind == JsonValueKind.Array
            ? root
            : root.ValueKind == JsonValueKind.Object && root.TryGetProperty("downloads", out JsonElement nested) ? nested : default;
        if (downloads.ValueKind != JsonValueKind.Array)
        {
            throw new InvalidDataException($"Legacy desktop download history '{path}' has no downloads array.");
        }
        return downloads.Deserialize<PersistedDownload[]>(new JsonSerializerOptions(JsonSerializerDefaults.Web)) ?? [];
    }

    private object? CreateCheckpointRecord(PersistedDownload download)
    {
        string path = download.DestinationPath + ".xdm.resume.json";
        if (!File.Exists(path))
        {
            return null;
        }
        try
        {
            using JsonDocument document = JsonDocument.Parse(File.ReadAllBytes(path));
            JsonElement root = document.RootElement;
            long downloaded = root.TryGetProperty("downloadedBytes", out JsonElement value) && value.TryGetInt64(out long parsed)
                ? Math.Max(0, parsed)
                : Math.Max(0, download.DownloadedBytes);
            return new
            {
                id = download.Id,
                payload = new
                {
                    download_id = download.Id,
                    downloaded_bytes = downloaded,
                    total_bytes = download.TotalBytes,
                    destination_path = download.DestinationPath
                }
            };
        }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException or JsonException)
        {
            return null;
        }
    }

    private async Task<(object Settings, object[] Queues, object[] Schedules)> LoadSettingsSummaryAsync(CancellationToken cancellationToken)
    {
        string path = Path.Combine(_legacyDirectory, "settings.json");
        if (!File.Exists(path))
        {
            return (new { id = "desktop-settings", payload = new { present = false } }, [], []);
        }
        try
        {
            await using FileStream stream = new(path, FileMode.Open, FileAccess.Read, FileShare.Read, 16 * 1024, FileOptions.Asynchronous | FileOptions.SequentialScan);
            using JsonDocument document = await JsonDocument.ParseAsync(stream, cancellationToken: cancellationToken).ConfigureAwait(false);
            JsonElement root = document.RootElement;
            int schema = root.TryGetProperty("schemaVersion", out JsonElement schemaElement) && schemaElement.TryGetInt32(out int schemaValue) ? schemaValue : 0;
            string defaultDirectory = root.TryGetProperty("defaultDownloadDirectory", out JsonElement directoryElement) ? directoryElement.GetString() ?? string.Empty : string.Empty;
            int maxConcurrent = root.TryGetProperty("maxConcurrentDownloads", out JsonElement maxElement) && maxElement.TryGetInt32(out int maxValue) ? maxValue : 0;
            object settings = new
            {
                id = "desktop-settings",
                payload = new
                {
                    present = true,
                    schema_version = schema,
                    default_download_directory = defaultDirectory,
                    max_concurrent_downloads = maxConcurrent
                }
            };
            object[] queues = ExtractDefinitions(root, "queues", "queue");
            object[] schedules = ExtractDefinitions(root, "schedules", "schedule");
            return (settings, queues, schedules);
        }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException or JsonException)
        {
            return (new { id = "desktop-settings", payload = new { present = false, unreadable = true } }, [], []);
        }
    }

    private static object[] ExtractDefinitions(JsonElement root, string propertyName, string fallbackPrefix)
    {
        if (!root.TryGetProperty(propertyName, out JsonElement array) || array.ValueKind != JsonValueKind.Array)
        {
            return [];
        }
        List<object> records = [];
        int index = 0;
        foreach (JsonElement item in array.EnumerateArray())
        {
            string id = item.TryGetProperty("id", out JsonElement idElement) ? idElement.GetString() ?? string.Empty : string.Empty;
            if (string.IsNullOrWhiteSpace(id))
            {
                id = fallbackPrefix + "-" + index.ToString(System.Globalization.CultureInfo.InvariantCulture);
            }
            string name = item.TryGetProperty("name", out JsonElement nameElement) ? nameElement.GetString() ?? string.Empty : string.Empty;
            records.Add(new { id, payload = new { name } });
            index++;
        }
        return records.ToArray();
    }

    private static string SafeProjectionUrl(Uri source)
    {
        if (!source.IsAbsoluteUri)
        {
            return source.ToString();
        }
        UriBuilder builder = new(source)
        {
            UserName = string.Empty,
            Password = string.Empty,
            Query = string.Empty,
            Fragment = string.Empty
        };
        return builder.Uri.AbsoluteUri;
    }
}
