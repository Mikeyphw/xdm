namespace XDM.Media;

public static class ConversionDestinationPlanner
{
    public static string CreatePostDownloadDestination(
        string sourcePath,
        ConversionPreset preset)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(sourcePath);
        ArgumentNullException.ThrowIfNull(preset);
        string fullSourcePath = Path.GetFullPath(sourcePath);
        string directory = Path.GetDirectoryName(fullSourcePath) ?? Environment.CurrentDirectory;
        string stem = Path.GetFileNameWithoutExtension(fullSourcePath);
        return Path.Combine(directory, $"{stem}.converted{preset.FileExtension}");
    }
}
