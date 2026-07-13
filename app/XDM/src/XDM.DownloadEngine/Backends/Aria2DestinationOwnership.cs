using XDM.DownloadEngine.Aria2;

namespace XDM.DownloadEngine.Backends;

public static class Aria2DestinationOwnership
{
    public static Aria2TaskSnapshot? FindCollision(
        string destinationPath,
        IEnumerable<Aria2TaskSnapshot> tasks,
        string? ownedGid = null)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(destinationPath);
        ArgumentNullException.ThrowIfNull(tasks);

        string target = Path.GetFullPath(destinationPath);
        return tasks.FirstOrDefault(task =>
            (task.Status is Aria2TaskStatus.Waiting or Aria2TaskStatus.Active or Aria2TaskStatus.Paused)
            && !string.Equals(task.Gid, ownedGid, StringComparison.Ordinal)
            && TryNormalizePath(task.DestinationPath, out string? candidate)
            && string.Equals(
                candidate,
                target,
                OperatingSystem.IsWindows() ? StringComparison.OrdinalIgnoreCase : StringComparison.Ordinal));
    }

    private static bool TryNormalizePath(string? path, out string? normalized)
    {
        normalized = null;
        if (string.IsNullOrWhiteSpace(path))
        {
            return false;
        }

        try
        {
            normalized = Path.GetFullPath(path);
            return true;
        }
        catch (Exception exception) when (exception is ArgumentException
            or NotSupportedException
            or PathTooLongException)
        {
            return false;
        }
    }
}
