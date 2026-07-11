using System.Text.Json;
using XDM.Core.Persistence;

namespace XDM.Persistence;

public sealed class JsonDownloadHistoryStore : IDownloadHistoryStore
{
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

    public async Task<IReadOnlyList<PersistedDownload>> LoadAsync(
        CancellationToken cancellationToken = default)
    {
        if (!File.Exists(_historyPath))
        {
            return [];
        }

        try
        {
            await using FileStream stream = new(
                _historyPath,
                FileMode.Open,
                FileAccess.Read,
                FileShare.Read,
                16 * 1024,
                FileOptions.Asynchronous | FileOptions.SequentialScan);

            PersistedDownload[]? downloads = await JsonSerializer
                .DeserializeAsync<PersistedDownload[]>(stream, SerializerOptions, cancellationToken)
                .ConfigureAwait(false);

            return downloads ?? [];
        }
        catch (JsonException)
        {
            QuarantineCorruptHistory();
            return [];
        }
    }

    public async Task SaveAsync(
        IReadOnlyCollection<PersistedDownload> downloads,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(downloads);

        string? directory = Path.GetDirectoryName(_historyPath);
        if (!string.IsNullOrWhiteSpace(directory))
        {
            Directory.CreateDirectory(directory);
        }

        string temporaryPath = $"{_historyPath}.tmp";
        await using (FileStream stream = new(
            temporaryPath,
            FileMode.Create,
            FileAccess.Write,
            FileShare.None,
            16 * 1024,
            FileOptions.Asynchronous | FileOptions.WriteThrough))
        {
            await JsonSerializer
                .SerializeAsync(stream, downloads, SerializerOptions, cancellationToken)
                .ConfigureAwait(false);
            await stream.FlushAsync(cancellationToken).ConfigureAwait(false);
        }

        File.Move(temporaryPath, _historyPath, overwrite: true);
    }

    private static string GetDefaultHistoryPath()
    {
        string baseDirectory = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        return Path.Combine(baseDirectory, "xdm-modern", "downloads.json");
    }

    private void QuarantineCorruptHistory()
    {
        string corruptPath = $"{_historyPath}.corrupt-{DateTimeOffset.UtcNow:yyyyMMddHHmmss}";
        try
        {
            File.Move(_historyPath, corruptPath, overwrite: true);
        }
        catch (IOException)
        {
            // Leave the original in place if the platform refuses the quarantine move.
        }
        catch (UnauthorizedAccessException)
        {
            // Loading remains recoverable even when the state directory is read-only.
        }
    }
}
