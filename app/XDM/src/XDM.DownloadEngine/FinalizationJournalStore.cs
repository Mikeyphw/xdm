using System.Globalization;
using System.Text;
using System.Text.Json;

namespace XDM.DownloadEngine;

public sealed class FinalizationJournalStore
{
    private readonly JsonSerializerOptions _serializerOptions = new(JsonSerializerDefaults.Web)
    {
        WriteIndented = true
    };

    public async Task<FinalizationMarker?> LoadAsync(
        string destinationPath,
        CancellationToken cancellationToken = default)
    {
        string path = TransferArtifactPaths.GetFinalizationMarkerPath(destinationPath);
        if (!File.Exists(path))
        {
            return null;
        }

        string payload = await File.ReadAllTextAsync(path, cancellationToken).ConfigureAwait(false);
        try
        {
            FinalizationMarker? marker = JsonSerializer.Deserialize<FinalizationMarker>(payload, _serializerOptions);
            if (marker is not null && (marker.Version is 1 or FinalizationMarker.CurrentVersion))
            {
                return marker.Version == FinalizationMarker.CurrentVersion
                    ? marker
                    : marker with
                    {
                        Version = FinalizationMarker.CurrentVersion,
                        Stage = FinalizationStage.Prepared,
                        UpdatedAt = marker.CreatedAt
                    };
            }
        }
        catch (JsonException)
        {
        }

        if (long.TryParse(payload, NumberStyles.Integer, CultureInfo.InvariantCulture, out long legacyLength))
        {
            return new FinalizationMarker(
                FinalizationMarker.CurrentVersion,
                legacyLength,
                null,
                null,
                DateTimeOffset.UtcNow,
                Stage: FinalizationStage.Prepared,
                UpdatedAt: DateTimeOffset.UtcNow);
        }

        throw new InvalidDataException("The finalization journal is invalid.");
    }

    public async Task SaveAsync(
        string destinationPath,
        FinalizationMarker marker,
        CancellationToken cancellationToken = default)
    {
        string path = TransferArtifactPaths.GetFinalizationMarkerPath(destinationPath);
        string temporaryPath = $"{path}.tmp";
        Directory.CreateDirectory(Path.GetDirectoryName(path) ?? Directory.GetCurrentDirectory());
        FinalizationMarker normalized = marker with
        {
            Version = FinalizationMarker.CurrentVersion,
            UpdatedAt = DateTimeOffset.UtcNow
        };
        byte[] payload = Encoding.UTF8.GetBytes(JsonSerializer.Serialize(normalized, _serializerOptions));
        await using (FileStream stream = new(
            temporaryPath,
            FileMode.Create,
            FileAccess.Write,
            FileShare.None,
            4096,
            FileOptions.Asynchronous | FileOptions.WriteThrough))
        {
            await stream.WriteAsync(payload, cancellationToken).ConfigureAwait(false);
            await stream.FlushAsync(cancellationToken).ConfigureAwait(false);
            stream.Flush(flushToDisk: true);
        }
        File.Move(temporaryPath, path, overwrite: true);
    }

    public static void Delete(string destinationPath)
    {
        string path = TransferArtifactPaths.GetFinalizationMarkerPath(destinationPath);
        if (File.Exists(path))
        {
            File.Delete(path);
        }
        string temporaryPath = $"{path}.tmp";
        if (File.Exists(temporaryPath))
        {
            File.Delete(temporaryPath);
        }
    }
}
