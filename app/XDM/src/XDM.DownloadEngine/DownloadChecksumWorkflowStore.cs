using System.Text.Json;

namespace XDM.DownloadEngine;

public sealed class DownloadChecksumWorkflowStore
{
    private readonly JsonSerializerOptions _serializerOptions = new(JsonSerializerDefaults.Web)
    {
        WriteIndented = true
    };

    public async Task<DownloadChecksumWorkflowState> LoadAsync(
        string destinationPath,
        CancellationToken cancellationToken = default)
    {
        string path = TransferArtifactPaths.GetChecksumStatePath(destinationPath);
        if (!File.Exists(path))
        {
            return DownloadChecksumWorkflowState.Empty(destinationPath);
        }

        try
        {
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
            return state is { Version: DownloadChecksumWorkflowState.CurrentVersion }
                ? state
                : DownloadChecksumWorkflowState.Empty(destinationPath);
        }
        catch (JsonException)
        {
            return DownloadChecksumWorkflowState.Empty(destinationPath);
        }
    }

    public async Task SaveAsync(
        DownloadChecksumWorkflowState state,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(state);
        string path = TransferArtifactPaths.GetChecksumStatePath(state.DestinationPath);
        string temporaryPath = $"{path}.tmp";
        string? directory = Path.GetDirectoryName(path);
        if (!string.IsNullOrWhiteSpace(directory))
        {
            Directory.CreateDirectory(directory);
        }

        try
        {
            await using (FileStream stream = new(
                temporaryPath,
                FileMode.Create,
                FileAccess.Write,
                FileShare.None,
                16 * 1024,
                FileOptions.Asynchronous | FileOptions.WriteThrough))
            {
                await JsonSerializer.SerializeAsync(stream, state, _serializerOptions, cancellationToken)
                    .ConfigureAwait(false);
                await stream.FlushAsync(cancellationToken).ConfigureAwait(false);
                stream.Flush(flushToDisk: true);
            }
            File.Move(temporaryPath, path, overwrite: true);
        }
        finally
        {
            if (File.Exists(temporaryPath))
            {
                File.Delete(temporaryPath);
            }
        }
    }
}
