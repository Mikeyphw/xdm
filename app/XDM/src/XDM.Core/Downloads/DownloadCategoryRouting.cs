using XDM.Core.Settings;

namespace XDM.Core.Downloads;

public sealed record DownloadCategoryRoute(string? CategoryId, string DestinationDirectory);

public static class DownloadCategoryRouting
{
    public static DownloadCategoryRoute Resolve(
        ApplicationSettings settings,
        Uri source,
        string? selectedCategoryId,
        string fallbackDestinationDirectory)
    {
        ArgumentNullException.ThrowIfNull(settings);
        ArgumentNullException.ThrowIfNull(source);
        ArgumentException.ThrowIfNullOrWhiteSpace(fallbackDestinationDirectory);

        DownloadBehaviorSettings behavior = settings.DownloadBehavior ?? DownloadBehaviorSettings.Default;
        if (!behavior.AutoSelectCategory)
        {
            return new(selectedCategoryId, fallbackDestinationDirectory);
        }

        string fileName = Uri.UnescapeDataString(Path.GetFileName(source.LocalPath));
        DownloadCategoryDefinition? category = settings.Categories
            .FirstOrDefault(item => item.Extensions.Any(extension =>
                fileName.EndsWith($".{extension.Trim().TrimStart('.')}", StringComparison.OrdinalIgnoreCase)));
        if (category is null)
        {
            return new(selectedCategoryId, fallbackDestinationDirectory);
        }

        string directory = string.IsNullOrWhiteSpace(category.DestinationDirectory)
            ? fallbackDestinationDirectory
            : category.DestinationDirectory;
        return new(category.Id, directory);
    }
}
