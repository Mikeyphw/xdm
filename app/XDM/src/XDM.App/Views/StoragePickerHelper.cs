using Avalonia.Controls;
using Avalonia.Platform.Storage;

namespace XDM.App.Views;

internal static class StoragePickerHelper
{
    public static async Task<string?> PickFolderAsync(Control owner, string title)
    {
        IStorageProvider? storageProvider = TopLevel.GetTopLevel(owner)?.StorageProvider;
        if (storageProvider is null)
        {
            return null;
        }

        IReadOnlyList<IStorageFolder> folders = await storageProvider.OpenFolderPickerAsync(
            new FolderPickerOpenOptions
            {
                AllowMultiple = false,
                Title = title,
            });
        return folders.Count > 0 ? folders[0].TryGetLocalPath() : null;
    }

    public static async Task<string?> PickFileAsync(Control owner, string title)
    {
        IStorageProvider? storageProvider = TopLevel.GetTopLevel(owner)?.StorageProvider;
        if (storageProvider is null)
        {
            return null;
        }

        IReadOnlyList<IStorageFile> files = await storageProvider.OpenFilePickerAsync(
            new FilePickerOpenOptions
            {
                AllowMultiple = false,
                Title = title,
            });
        return files.Count > 0 ? files[0].TryGetLocalPath() : null;
    }

    public static async Task<string?> SaveFileAsync(
        Control owner,
        string title,
        string suggestedFileName,
        string? defaultExtension = null)
    {
        IStorageProvider? storageProvider = TopLevel.GetTopLevel(owner)?.StorageProvider;
        if (storageProvider is null)
        {
            return null;
        }

        FilePickerSaveOptions options = new()
        {
            Title = title,
            SuggestedFileName = suggestedFileName,
        };
        if (!string.IsNullOrWhiteSpace(defaultExtension))
        {
            options.DefaultExtension = defaultExtension;
        }

        IStorageFile? file = await storageProvider.SaveFilePickerAsync(options);
        return file?.TryGetLocalPath();
    }
}
