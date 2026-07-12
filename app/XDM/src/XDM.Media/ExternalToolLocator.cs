namespace XDM.Media;

public static class ExternalToolLocator
{
    public static string? Find(string baseName)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(baseName);
        string executableName = OperatingSystem.IsWindows() && !baseName.EndsWith(".exe", StringComparison.OrdinalIgnoreCase)
            ? $"{baseName}.exe"
            : baseName;
        string applicationCandidate = Path.Combine(AppContext.BaseDirectory, executableName);
        if (File.Exists(applicationCandidate))
        {
            return Path.GetFullPath(applicationCandidate);
        }

        string? path = Environment.GetEnvironmentVariable("PATH");
        if (string.IsNullOrWhiteSpace(path))
        {
            return null;
        }

        foreach (string directory in path.Split(Path.PathSeparator, StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
        {
            try
            {
                string candidate = Path.Combine(directory, executableName);
                if (File.Exists(candidate))
                {
                    return Path.GetFullPath(candidate);
                }
            }
            catch (ArgumentException)
            {
            }
            catch (NotSupportedException)
            {
            }
        }

        return null;
    }
}
