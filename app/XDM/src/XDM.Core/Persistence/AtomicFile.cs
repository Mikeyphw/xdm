namespace XDM.Core.Persistence;

public static class AtomicFile
{
    public static async Task WriteAsync(
        string destinationPath,
        Func<Stream, Task> writer,
        bool createBackup = true,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(destinationPath);
        ArgumentNullException.ThrowIfNull(writer);

        string fullPath = Path.GetFullPath(destinationPath);
        string? directory = Path.GetDirectoryName(fullPath);
        if (!string.IsNullOrWhiteSpace(directory))
        {
            Directory.CreateDirectory(directory);
        }

        string temporaryPath = $"{fullPath}.tmp-{Environment.ProcessId}-{Guid.NewGuid():N}";
        string backupPath = $"{fullPath}.bak";
        try
        {
            await using (FileStream stream = new(
                temporaryPath,
                FileMode.CreateNew,
                FileAccess.Write,
                FileShare.None,
                16 * 1024,
                FileOptions.Asynchronous | FileOptions.WriteThrough))
            {
                await writer(stream).ConfigureAwait(false);
                await stream.FlushAsync(cancellationToken).ConfigureAwait(false);
                stream.Flush(flushToDisk: true);
            }

            cancellationToken.ThrowIfCancellationRequested();
            if (createBackup && File.Exists(fullPath))
            {
                File.Copy(fullPath, backupPath, overwrite: true);
            }

            File.Move(temporaryPath, fullPath, overwrite: true);
        }
        finally
        {
            try
            {
                if (File.Exists(temporaryPath))
                {
                    File.Delete(temporaryPath);
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

    public static Task WriteAllBytesAsync(
        string destinationPath,
        ReadOnlyMemory<byte> payload,
        CancellationToken cancellationToken = default,
        bool createBackup = false)
        => WriteAsync(
            destinationPath,
            async stream => await stream.WriteAsync(payload, cancellationToken).ConfigureAwait(false),
            createBackup,
            cancellationToken);

    public static void Quarantine(string path, string reason)
    {
        if (!File.Exists(path))
        {
            return;
        }

        string safeReason = string.Concat(reason.Where(static c => char.IsLetterOrDigit(c) || c is '-' or '_'));
        string quarantine = $"{path}.{safeReason}-{DateTimeOffset.UtcNow:yyyyMMddHHmmssfff}";
        try
        {
            File.Move(path, quarantine, overwrite: false);
        }
        catch (IOException)
        {
        }
        catch (UnauthorizedAccessException)
        {
        }
    }
}
