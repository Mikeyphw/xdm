using System.Text.Json;

namespace XDM.DownloadEngine;

public sealed class ResumeCheckpointStore
{
    private readonly Func<string, string> _checkpointPathResolver;

    public ResumeCheckpointStore()
        : this(TransferArtifactPaths.GetCheckpointPath)
    {
    }

    private ResumeCheckpointStore(Func<string, string> checkpointPathResolver)
    {
        _checkpointPathResolver = checkpointPathResolver ?? throw new ArgumentNullException(nameof(checkpointPathResolver));
    }

    private const long MaximumCheckpointBytes = 2L * 1024 * 1024;
    private static readonly JsonSerializerOptions SerializerOptions = new(JsonSerializerDefaults.Web)
    {
        WriteIndented = true
    };

    public async Task<ResumeCheckpoint?> LoadAsync(
        string destinationPath,
        CancellationToken cancellationToken = default)
    {
        string path = _checkpointPathResolver(destinationPath);
        if (!File.Exists(path))
        {
            return null;
        }

        if (new FileInfo(path).Length > MaximumCheckpointBytes)
        {
            Quarantine(path);
            return null;
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
            ResumeCheckpoint? checkpoint = await JsonSerializer
                .DeserializeAsync<ResumeCheckpoint>(stream, SerializerOptions, cancellationToken)
                .ConfigureAwait(false);
            return checkpoint is { Version: ResumeCheckpoint.CurrentVersion } ? checkpoint : null;
        }
        catch (JsonException)
        {
            Quarantine(path);
            return null;
        }
    }

    public async Task SaveAsync(
        ResumeCheckpoint checkpoint,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(checkpoint);
        string path = _checkpointPathResolver(checkpoint.DestinationPath);
        string? directory = Path.GetDirectoryName(path);
        if (!string.IsNullOrWhiteSpace(directory))
        {
            Directory.CreateDirectory(directory);
        }

        string temporaryPath = $"{path}.tmp";
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
                await JsonSerializer
                    .SerializeAsync(stream, checkpoint, SerializerOptions, cancellationToken)
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

    public void Delete(string destinationPath)
    {
        string path = _checkpointPathResolver(destinationPath);
        if (File.Exists(path))
        {
            File.Delete(path);
        }
    }

    private static void Quarantine(string path)
    {
        string quarantinePath = $"{path}.corrupt-{DateTimeOffset.UtcNow:yyyyMMddHHmmss}";
        try
        {
            File.Move(path, quarantinePath, overwrite: true);
        }
        catch (IOException)
        {
        }
        catch (UnauthorizedAccessException)
        {
        }
    }
}
