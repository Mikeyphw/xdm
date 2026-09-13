using System.Globalization;
using System.Text;
using System.Text.Json;
using XDM.Core.Persistence;

namespace XDM.DownloadEngine;

public sealed class FinalizationJournalStore
{
    private const long MaximumJournalBytes = 2L * 1024 * 1024;
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

        try
        {
            if (new FileInfo(path).Length > MaximumJournalBytes)
            {
                AtomicFile.Quarantine(path, "oversized");
                return null;
            }

            string payload = await File.ReadAllTextAsync(path, cancellationToken).ConfigureAwait(false);
            try
            {
                FinalizationMarker? marker = JsonSerializer.Deserialize<FinalizationMarker>(payload, _serializerOptions);
                if (marker is not null && marker.Version is >= 1 and <= FinalizationMarker.CurrentVersion)
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

            AtomicFile.Quarantine(path, "corrupt");
            return null;
        }
        catch (IOException)
        {
            return null;
        }
        catch (UnauthorizedAccessException)
        {
            return null;
        }
    }

    public Task SaveAsync(
        string destinationPath,
        FinalizationMarker marker,
        CancellationToken cancellationToken = default)
    {
        string path = TransferArtifactPaths.GetFinalizationMarkerPath(destinationPath);
        FinalizationMarker normalized = marker with
        {
            Version = FinalizationMarker.CurrentVersion,
            UpdatedAt = DateTimeOffset.UtcNow
        };
        byte[] payload = Encoding.UTF8.GetBytes(JsonSerializer.Serialize(normalized, _serializerOptions));
        return AtomicFile.WriteAllBytesAsync(path, payload, cancellationToken);
    }

    public static void Delete(string destinationPath)
    {
        string path = TransferArtifactPaths.GetFinalizationMarkerPath(destinationPath);
        DeleteIfExists(path);
        DeleteIfExists($"{path}.tmp");
    }

    private static void DeleteIfExists(string path)
    {
        try
        {
            if (File.Exists(path))
            {
                File.Delete(path);
            }
        }
        catch (IOException)
        {
        }
        catch (UnauthorizedAccessException)
        {
        }
    }
}
