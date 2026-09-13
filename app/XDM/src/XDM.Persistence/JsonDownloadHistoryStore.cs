using System.Text.Json;
using XDM.Core.Persistence;

namespace XDM.Persistence;

public sealed class JsonDownloadHistoryStore : IDownloadHistoryStore
{
    private const int CurrentSchemaVersion = 1;
    private const string Format = "xdm-modern-download-history";
    private static readonly JsonSerializerOptions SerializerOptions = new(JsonSerializerDefaults.Web)
    {
        WriteIndented = true
    };

    private readonly string _historyPath;

    public JsonDownloadHistoryStore()
        : this(GetDefaultHistoryPath())
    {
    }

    public JsonDownloadHistoryStore(string historyPath)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(historyPath);
        _historyPath = historyPath;
    }

    public async Task<IReadOnlyList<PersistedDownload>> LoadAsync(CancellationToken cancellationToken = default)
    {
        if (!File.Exists(_historyPath))
        {
            return await TryLoadBackupAsync(cancellationToken).ConfigureAwait(false) ?? [];
        }

        try
        {
            return await LoadFileAsync(_historyPath, cancellationToken).ConfigureAwait(false);
        }
        catch (UnsupportedHistorySchemaException)
        {
            AtomicFile.Quarantine(_historyPath, "incompatible");
            throw;
        }
        catch (Exception exception) when (exception is JsonException or IOException or UnauthorizedAccessException or InvalidDataException)
        {
            AtomicFile.Quarantine(_historyPath, "corrupt");
            return await TryLoadBackupAsync(cancellationToken).ConfigureAwait(false) ?? [];
        }
    }

    public Task SaveAsync(
        IReadOnlyCollection<PersistedDownload> downloads,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(downloads);
        PersistedDownload[] validated = Validate(downloads).ToArray();
        HistoryEnvelope envelope = new(Format, CurrentSchemaVersion, DateTimeOffset.UtcNow, validated);
        return AtomicFile.WriteAsync(
            _historyPath,
            stream => JsonSerializer.SerializeAsync(
                stream,
                envelope,
                SerializerOptions,
                cancellationToken),
            createBackup: true,
            cancellationToken);
    }

    private async Task<IReadOnlyList<PersistedDownload>?> TryLoadBackupAsync(CancellationToken cancellationToken)
    {
        string backupPath = GetBackupPath();
        if (!File.Exists(backupPath))
        {
            return null;
        }

        try
        {
            return await LoadFileAsync(backupPath, cancellationToken).ConfigureAwait(false);
        }
        catch (Exception exception) when (exception is JsonException or IOException or UnauthorizedAccessException or InvalidDataException)
        {
            AtomicFile.Quarantine(backupPath, exception is UnsupportedHistorySchemaException ? "incompatible" : "corrupt");
            return null;
        }
    }

    private static async Task<IReadOnlyList<PersistedDownload>> LoadFileAsync(
        string path,
        CancellationToken cancellationToken)
    {
        await using FileStream stream = new(
            path,
            FileMode.Open,
            FileAccess.Read,
            FileShare.Read,
            16 * 1024,
            FileOptions.Asynchronous | FileOptions.SequentialScan);

        using JsonDocument document = await JsonDocument.ParseAsync(
            stream,
            cancellationToken: cancellationToken).ConfigureAwait(false);

        PersistedDownload[] downloads;
        if (document.RootElement.ValueKind == JsonValueKind.Array)
        {
            downloads = document.RootElement.Deserialize<PersistedDownload[]>(SerializerOptions) ?? [];
        }
        else
        {
            HistoryEnvelope? envelope = document.RootElement.Deserialize<HistoryEnvelope>(SerializerOptions);
            if (envelope is null
                || !string.Equals(envelope.Format, Format, StringComparison.Ordinal)
                || envelope.SchemaVersion is < 1 or > CurrentSchemaVersion)
            {
                throw new UnsupportedHistorySchemaException("The download-history schema is unsupported.");
            }

            downloads = envelope.Downloads?.ToArray() ?? [];
        }

        return Validate(downloads).ToArray();
    }

    private static IEnumerable<PersistedDownload> Validate(IEnumerable<PersistedDownload> downloads)
    {
        HashSet<string> ids = new(StringComparer.Ordinal);
        HashSet<string> destinations = new(
            OperatingSystem.IsWindows() ? StringComparer.OrdinalIgnoreCase : StringComparer.Ordinal);

        foreach (PersistedDownload item in downloads)
        {
            if (string.IsNullOrWhiteSpace(item.Id))
            {
                throw new InvalidDataException("Download history contains an entry without an id.");
            }
            if (!ids.Add(item.Id))
            {
                throw new InvalidDataException($"Download history contains duplicate id '{item.Id}'.");
            }

            string fullDestination;
            try
            {
                fullDestination = Path.GetFullPath(item.DestinationPath);
            }
            catch (Exception exception) when (exception is ArgumentException or NotSupportedException or PathTooLongException)
            {
                throw new InvalidDataException($"Download '{item.Id}' has an invalid destination path.", exception);
            }

            if (!destinations.Add(fullDestination))
            {
                throw new InvalidDataException(
                    $"Download history contains multiple entries for destination '{fullDestination}'.");
            }
            yield return item with { DestinationPath = fullDestination };
        }
    }

    private static string GetDefaultHistoryPath()
    {
        string baseDirectory = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        return Path.Combine(baseDirectory, "xdm-modern", "downloads.json");
    }

    private string GetBackupPath() => $"{_historyPath}.bak";

    private sealed class UnsupportedHistorySchemaException(string message) : InvalidDataException(message);

    private sealed record HistoryEnvelope(
        string Format,
        int SchemaVersion,
        DateTimeOffset SavedAt,
        IReadOnlyList<PersistedDownload> Downloads);
}
