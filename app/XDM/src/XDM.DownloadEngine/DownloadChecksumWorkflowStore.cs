using System.Text.Json;
using XDM.Core.Persistence;

namespace XDM.DownloadEngine;

public sealed class DownloadChecksumWorkflowStore
{
    private const long MaximumStateBytes = 2L * 1024 * 1024;
    private readonly JsonSerializerOptions _serializerOptions = new(JsonSerializerDefaults.Web)
    {
        WriteIndented = true
    };

    public Task<DownloadChecksumWorkflowState> LoadAsync(
        string destinationPath,
        CancellationToken cancellationToken = default)
        => LoadAsync(destinationPath, ownerDownloadId: null, cancellationToken);

    public async Task<DownloadChecksumWorkflowState> LoadAsync(
        string destinationPath,
        string? ownerDownloadId,
        CancellationToken cancellationToken = default)
    {
        string path = TransferArtifactPaths.GetChecksumStatePath(destinationPath);
        if (!File.Exists(path))
        {
            return DownloadChecksumWorkflowState.Empty(destinationPath, ownerDownloadId);
        }

        try
        {
            if (new FileInfo(path).Length > MaximumStateBytes)
            {
                AtomicFile.Quarantine(path, "oversized");
                return DownloadChecksumWorkflowState.Empty(destinationPath, ownerDownloadId);
            }

            await using FileStream stream = new(
                path,
                FileMode.Open,
                FileAccess.Read,
                FileShare.Read,
                16 * 1024,
                FileOptions.Asynchronous | FileOptions.SequentialScan);
            DownloadChecksumWorkflowState? state = await JsonSerializer.DeserializeAsync<DownloadChecksumWorkflowState>(
                stream,
                _serializerOptions,
                cancellationToken).ConfigureAwait(false);
            if (state is null || state.Version is < 1 or > DownloadChecksumWorkflowState.CurrentVersion)
            {
                AtomicFile.Quarantine(path, "incompatible");
                return DownloadChecksumWorkflowState.Empty(destinationPath, ownerDownloadId);
            }
            if (!PathsEqual(state.DestinationPath, destinationPath))
            {
                AtomicFile.Quarantine(path, "foreign-destination");
                return DownloadChecksumWorkflowState.Empty(destinationPath, ownerDownloadId);
            }
            if (state.Version == DownloadChecksumWorkflowState.CurrentVersion
                && !string.IsNullOrWhiteSpace(ownerDownloadId)
                && !string.Equals(state.OwnerDownloadId, ownerDownloadId, StringComparison.Ordinal))
            {
                AtomicFile.Quarantine(path, "foreign-owner");
                return DownloadChecksumWorkflowState.Empty(destinationPath, ownerDownloadId);
            }

            return state with
            {
                Version = DownloadChecksumWorkflowState.CurrentVersion,
                DestinationPath = Path.GetFullPath(destinationPath),
                OwnerDownloadId = ownerDownloadId ?? state.OwnerDownloadId
            };
        }
        catch (JsonException)
        {
            AtomicFile.Quarantine(path, "corrupt");
            return DownloadChecksumWorkflowState.Empty(destinationPath, ownerDownloadId);
        }
        catch (IOException)
        {
            return DownloadChecksumWorkflowState.Empty(destinationPath, ownerDownloadId);
        }
        catch (UnauthorizedAccessException)
        {
            return DownloadChecksumWorkflowState.Empty(destinationPath, ownerDownloadId);
        }
    }

    public Task SaveAsync(
        DownloadChecksumWorkflowState state,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(state);
        DownloadChecksumWorkflowState normalized = state with
        {
            Version = DownloadChecksumWorkflowState.CurrentVersion,
            DestinationPath = Path.GetFullPath(state.DestinationPath)
        };
        string path = TransferArtifactPaths.GetChecksumStatePath(normalized.DestinationPath);
        return AtomicFile.WriteAsync(
            path,
            stream => JsonSerializer.SerializeAsync(stream, normalized, _serializerOptions, cancellationToken),
            createBackup: false,
            cancellationToken);
    }

    private static bool PathsEqual(string left, string right)
        => string.Equals(
            Path.GetFullPath(left),
            Path.GetFullPath(right),
            OperatingSystem.IsWindows() ? StringComparison.OrdinalIgnoreCase : StringComparison.Ordinal);
}
