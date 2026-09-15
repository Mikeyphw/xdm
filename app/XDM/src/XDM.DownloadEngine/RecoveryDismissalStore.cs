using System.Text.Json;
using XDM.Core.Persistence;

namespace XDM.DownloadEngine;

internal sealed record RecoveryDismissalMarker(
    int Version,
    string DestinationPath,
    string CandidateId,
    DateTimeOffset DismissedAt)
{
    public const int CurrentVersion = 1;
}

internal static class RecoveryDismissalStore
{
    private static readonly JsonSerializerOptions SerializerOptions = new(JsonSerializerDefaults.Web)
    {
        WriteIndented = true
    };

    public static async Task<bool> IsDismissedAsync(
        string destinationPath,
        CancellationToken cancellationToken = default)
    {
        string path = TransferArtifactPaths.GetRecoveryDismissalPath(destinationPath);
        if (!File.Exists(path))
        {
            return false;
        }

        try
        {
            await using FileStream stream = new(
                path,
                FileMode.Open,
                FileAccess.Read,
                FileShare.Read,
                4096,
                FileOptions.Asynchronous | FileOptions.SequentialScan);
            RecoveryDismissalMarker? marker = await JsonSerializer
                .DeserializeAsync<RecoveryDismissalMarker>(stream, SerializerOptions, cancellationToken)
                .ConfigureAwait(false);
            if (marker is not { Version: RecoveryDismissalMarker.CurrentVersion }
                || !PathsEqual(marker.DestinationPath, destinationPath))
            {
                AtomicFile.Quarantine(path, "foreign");
                return false;
            }
            return true;
        }
        catch (JsonException)
        {
            AtomicFile.Quarantine(path, "corrupt");
            return false;
        }
        catch (IOException)
        {
            return false;
        }
        catch (UnauthorizedAccessException)
        {
            return false;
        }
    }

    public static Task SaveAsync(
        string destinationPath,
        string candidateId,
        CancellationToken cancellationToken = default)
    {
        RecoveryDismissalMarker marker = new(
            RecoveryDismissalMarker.CurrentVersion,
            Path.GetFullPath(destinationPath),
            candidateId,
            DateTimeOffset.UtcNow);
        byte[] payload = JsonSerializer.SerializeToUtf8Bytes(marker, SerializerOptions);
        return AtomicFile.WriteAllBytesAsync(
            TransferArtifactPaths.GetRecoveryDismissalPath(destinationPath),
            payload,
            cancellationToken);
    }

    public static void Delete(string destinationPath)
    {
        string path = TransferArtifactPaths.GetRecoveryDismissalPath(destinationPath);
        DeleteIfExists(path);
        DeleteIfExists($"{path}.tmp");
    }

    private static bool PathsEqual(string left, string right)
        => string.Equals(
            Path.GetFullPath(left),
            Path.GetFullPath(right),
            OperatingSystem.IsWindows() ? StringComparison.OrdinalIgnoreCase : StringComparison.Ordinal);

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
