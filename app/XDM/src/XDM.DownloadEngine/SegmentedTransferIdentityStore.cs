using System.Text.Json;
using XDM.Core.Persistence;

namespace XDM.DownloadEngine;

internal sealed record SegmentedTransferIdentity(
    int Version,
    long TotalBytes,
    string? EntityTag,
    DateTimeOffset? LastModified)
{
    public const int CurrentVersion = 1;

    public TransferIdentity ToTransferIdentity() => new(TotalBytes, EntityTag, LastModified);
}

internal static class SegmentedTransferIdentityStore
{
    private const string FileName = ".identity.json";
    private static readonly JsonSerializerOptions SerializerOptions = new(JsonSerializerDefaults.Web)
    {
        WriteIndented = true
    };

    public static string GetPath(string segmentDirectory) => Path.Combine(segmentDirectory, FileName);

    public static async Task<SegmentedTransferIdentity?> LoadAsync(
        string segmentDirectory,
        CancellationToken cancellationToken)
    {
        string path = GetPath(segmentDirectory);
        if (!File.Exists(path))
        {
            return null;
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
            SegmentedTransferIdentity? identity = await JsonSerializer
                .DeserializeAsync<SegmentedTransferIdentity>(stream, SerializerOptions, cancellationToken)
                .ConfigureAwait(false);
            return identity is { Version: SegmentedTransferIdentity.CurrentVersion } ? identity : null;
        }
        catch (JsonException)
        {
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

    public static Task SaveAsync(
        string segmentDirectory,
        SegmentedTransferIdentity identity,
        CancellationToken cancellationToken)
    {
        Directory.CreateDirectory(segmentDirectory);
        byte[] payload = JsonSerializer.SerializeToUtf8Bytes(identity, SerializerOptions);
        return AtomicFile.WriteAllBytesAsync(GetPath(segmentDirectory), payload, cancellationToken);
    }
}
