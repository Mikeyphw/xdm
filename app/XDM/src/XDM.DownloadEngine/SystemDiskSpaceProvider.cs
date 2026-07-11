namespace XDM.DownloadEngine;

public sealed class SystemDiskSpaceProvider : IDiskSpaceProvider
{
    public long? GetAvailableBytes(string path)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(path);

        try
        {
            string fullPath = Path.GetFullPath(path);
            string? root = Path.GetPathRoot(fullPath);
            if (string.IsNullOrWhiteSpace(root))
            {
                return null;
            }

            return new DriveInfo(root).AvailableFreeSpace;
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
}
