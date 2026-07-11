namespace XDM.Core.Categories;

public sealed record DownloadCategory
{
    public DownloadCategory(
        string id,
        string displayName,
        IEnumerable<string> fileExtensions,
        string defaultFolder,
        bool isPredefined = false)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(id);
        ArgumentException.ThrowIfNullOrWhiteSpace(displayName);
        ArgumentNullException.ThrowIfNull(fileExtensions);
        ArgumentException.ThrowIfNullOrWhiteSpace(defaultFolder);

        Id = id;
        DisplayName = displayName;
        DefaultFolder = defaultFolder;
        IsPredefined = isPredefined;
        FileExtensions = fileExtensions
            .Select(NormalizeExtension)
            .Where(static extension => extension.Length > 0)
            .ToHashSet(StringComparer.OrdinalIgnoreCase);
    }

    public string Id { get; }

    public string DisplayName { get; }

    public IReadOnlySet<string> FileExtensions { get; }

    public string DefaultFolder { get; }

    public bool IsPredefined { get; }

    public bool MatchesFileName(string fileName)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(fileName);

        return FileExtensions.Any(extension =>
            fileName.EndsWith($".{extension}", StringComparison.OrdinalIgnoreCase));
    }

    private static string NormalizeExtension(string extension)
        => extension.Trim().TrimStart('.').ToLowerInvariant();
}
