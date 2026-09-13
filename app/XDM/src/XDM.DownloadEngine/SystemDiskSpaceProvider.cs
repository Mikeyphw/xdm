namespace XDM.DownloadEngine;

public sealed class SystemDiskSpaceProvider : IDiskSpaceProvider
{
    public long? GetAvailableBytes(string path)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(path);

        try
        {
            string fullPath = Path.GetFullPath(path);
            DriveInfo? drive = DriveInfo.GetDrives()
                .Where(static candidate => candidate.IsReady)
                .Select(candidate => new
                {
                    Drive = candidate,
                    Root = NormalizeRoot(candidate.RootDirectory.FullName)
                })
                .Where(candidate => IsPathOnRoot(fullPath, candidate.Root))
                .OrderByDescending(static candidate => candidate.Root.Length)
                .Select(static candidate => candidate.Drive)
                .FirstOrDefault();

            if (drive is not null)
            {
                return drive.AvailableFreeSpace;
            }

            string? root = Path.GetPathRoot(fullPath);
            return string.IsNullOrWhiteSpace(root)
                ? null
                : new DriveInfo(root).AvailableFreeSpace;
        }
        catch (ArgumentException)
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

    private static bool IsPathOnRoot(string fullPath, string root)
    {
        StringComparison comparison = OperatingSystem.IsWindows()
            ? StringComparison.OrdinalIgnoreCase
            : StringComparison.Ordinal;
        if (!fullPath.StartsWith(root, comparison))
        {
            return false;
        }

        if (fullPath.Length == root.Length || root.EndsWith(Path.DirectorySeparatorChar))
        {
            return true;
        }

        char next = fullPath[root.Length];
        return next is '/' or '\\';
    }

    private static string NormalizeRoot(string root)
    {
        string full = Path.GetFullPath(root);
        return full.Length > 1
            ? full.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar)
            : full;
    }
}
