using System.Diagnostics;
using XDM.Core.Abstractions;

namespace XDM.Platform;

public sealed class DesktopPlatformService : IPlatformService
{
    public Task OpenFileAsync(string path, CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(path);
        cancellationToken.ThrowIfCancellationRequested();
        string fullPath = Path.GetFullPath(path);
        if (!File.Exists(fullPath))
        {
            throw new FileNotFoundException("The downloaded file does not exist.", fullPath);
        }

        StartShellTarget(fullPath);
        return Task.CompletedTask;
    }

    public Task RevealFileAsync(string path, CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(path);
        cancellationToken.ThrowIfCancellationRequested();
        string fullPath = Path.GetFullPath(path);
        string directory = Directory.Exists(fullPath)
            ? fullPath
            : Path.GetDirectoryName(fullPath)
                ?? throw new DirectoryNotFoundException("The destination directory could not be resolved.");
        if (!Directory.Exists(directory))
        {
            throw new DirectoryNotFoundException($"The destination directory does not exist: {directory}");
        }

        StartShellTarget(directory);
        return Task.CompletedTask;
    }

    public Task OpenUriAsync(Uri uri, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(uri);
        cancellationToken.ThrowIfCancellationRequested();
        if (!uri.IsAbsoluteUri || uri.Scheme is not ("http" or "https"))
        {
            throw new ArgumentException("Only absolute HTTP and HTTPS URLs can be opened.", nameof(uri));
        }

        StartShellTarget(uri.AbsoluteUri);
        return Task.CompletedTask;
    }

    private static void StartShellTarget(string target)
    {
        ProcessStartInfo startInfo = new()
        {
            FileName = target,
            UseShellExecute = true
        };
        using Process? process = Process.Start(startInfo);
    }
}
