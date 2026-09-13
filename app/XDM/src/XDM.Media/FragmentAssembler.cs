namespace XDM.Media;

internal static class FragmentAssembler
{
    public static async Task AssembleAsync(
        IReadOnlyList<string> fragmentPaths,
        string destinationPath,
        string emptyMessage,
        CancellationToken cancellationToken)
    {
        if (fragmentPaths.Count == 0)
        {
            throw new InvalidDataException(emptyMessage);
        }

        string fullDestination = Path.GetFullPath(destinationPath);
        Directory.CreateDirectory(Path.GetDirectoryName(fullDestination)!);
        string temporaryPath = $"{fullDestination}.assembling";
        bool completed = false;
        try
        {
            await using (FileStream destination = new(
                temporaryPath,
                FileMode.Create,
                FileAccess.Write,
                FileShare.None,
                128 * 1024,
                FileOptions.Asynchronous | FileOptions.SequentialScan))
            {
                foreach (string fragment in fragmentPaths)
                {
                    cancellationToken.ThrowIfCancellationRequested();
                    await using FileStream source = new(
                        fragment,
                        FileMode.Open,
                        FileAccess.Read,
                        FileShare.Read,
                        128 * 1024,
                        FileOptions.Asynchronous | FileOptions.SequentialScan);
                    await source.CopyToAsync(destination, cancellationToken).ConfigureAwait(false);
                }

                await destination.FlushAsync(cancellationToken).ConfigureAwait(false);
            }

            File.Move(temporaryPath, fullDestination, overwrite: true);
            completed = true;
        }
        finally
        {
            if (!completed && File.Exists(temporaryPath))
            {
                File.Delete(temporaryPath);
            }
        }
    }
}
