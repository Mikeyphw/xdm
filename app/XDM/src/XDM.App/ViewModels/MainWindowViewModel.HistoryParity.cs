using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using XDM.Core.Abstractions;
using XDM.Core.Downloads;
using XDM.Core.Persistence;
using XDM.Core.Settings;
using XDM.DownloadEngine;

namespace XDM.App.ViewModels;

public partial class MainWindowViewModel
{
    private readonly IDownloadListTransferService _downloadListTransferService;
    private readonly IPlatformService _platformService;

    [ObservableProperty]
    private bool historyRetentionEnabled;

    [ObservableProperty]
    private string historyRetentionDays = "90";

    [ObservableProperty]
    private string historyMaximumEntries = "10000";

    [ObservableProperty]
    private string historyTransferPath = string.Empty;

    [ObservableProperty]
    private string historyTransferStatus = "Import a plain URL list or versioned XDM download-list JSON.";

    [ObservableProperty]
    private string refreshedDownloadUrl = string.Empty;

    [ObservableProperty]
    private string refreshedSourcePage = string.Empty;

    [ObservableProperty]
    private string relocationDestinationPath = string.Empty;

    [ObservableProperty]
    private bool overwriteRelocationDestination;

    [RelayCommand]
    private async Task DeleteSelectedFileAndHistoryAsync()
    {
        if (SelectedDownload is null)
        {
            return;
        }

        string fileName = SelectedDownload.FileName;
        try
        {
            await _downloadManager.DeleteAsync(
                SelectedDownload.Id,
                DownloadDeletionScope.HistoryAndDownloadedFile);
            OperationMessage = $"Deleted '{fileName}' and removed its history entry.";
        }
        catch (IOException exception)
        {
            OperationMessage = exception.Message;
        }
        catch (UnauthorizedAccessException exception)
        {
            OperationMessage = exception.Message;
        }
    }

    [RelayCommand]
    private async Task DeleteSelectedPartialDataAsync()
    {
        if (SelectedDownload is null)
        {
            return;
        }

        try
        {
            await _downloadManager.DeleteAsync(
                SelectedDownload.Id,
                DownloadDeletionScope.HistoryAndPartialData);
            OperationMessage = "Partial transfer data and history entry deleted.";
        }
        catch (IOException exception)
        {
            OperationMessage = exception.Message;
        }
        catch (UnauthorizedAccessException exception)
        {
            OperationMessage = exception.Message;
        }
    }

    [RelayCommand]
    private async Task RelocateSelectedDownloadAsync()
    {
        if (SelectedDownload is null || string.IsNullOrWhiteSpace(RelocationDestinationPath))
        {
            OperationMessage = "Select a completed download and choose a new path.";
            return;
        }

        try
        {
            await _downloadManager.RelocateAsync(
                SelectedDownload.Id,
                RelocationDestinationPath.Trim(),
                OverwriteRelocationDestination);
            OperationMessage = "Downloaded file moved and history updated.";
        }
        catch (IOException exception)
        {
            OperationMessage = exception.Message;
        }
        catch (UnauthorizedAccessException exception)
        {
            OperationMessage = exception.Message;
        }
        catch (InvalidOperationException exception)
        {
            OperationMessage = exception.Message;
        }
    }

    [RelayCommand]
    private async Task RedownloadSelectedAsync()
    {
        if (SelectedDownload is null)
        {
            return;
        }

        try
        {
            string id = await _downloadManager.RedownloadAsync(SelectedDownload.Id);
            OperationMessage = $"Re-download queued as {id[..8]}.";
        }
        catch (InvalidOperationException exception)
        {
            OperationMessage = exception.Message;
        }
        catch (IOException exception)
        {
            OperationMessage = exception.Message;
        }
        catch (UnauthorizedAccessException exception)
        {
            OperationMessage = exception.Message;
        }
    }

    [RelayCommand]
    private async Task RefreshSelectedDownloadUrlAsync()
    {
        if (SelectedDownload is null
            || !Uri.TryCreate(RefreshedDownloadUrl.Trim(), UriKind.Absolute, out Uri? source)
            || !XDM.Core.Product.ModernFeaturePolicy.IsSupportedDownloadUri(source))
        {
            OperationMessage = "Enter a valid absolute HTTP, HTTPS, FTP, or FTPS replacement URL.";
            return;
        }

        Uri? sourcePage = null;
        if (!string.IsNullOrWhiteSpace(RefreshedSourcePage)
            && (!Uri.TryCreate(RefreshedSourcePage.Trim(), UriKind.Absolute, out sourcePage)
                || sourcePage.Scheme is not ("http" or "https")))
        {
            OperationMessage = "The source-page URL must use HTTP or HTTPS.";
            return;
        }

        try
        {
            await _downloadManager.RefreshSourceAsync(SelectedDownload.Id, source, sourcePage);
            OperationMessage = "Download URL refreshed. Resume or retry when ready.";
        }
        catch (ArgumentException exception)
        {
            OperationMessage = exception.Message;
        }
        catch (InvalidOperationException exception)
        {
            OperationMessage = exception.Message;
        }
    }

    [RelayCommand]
    private Task OpenSelectedFileAsync()
        => SelectedDownload is null
            ? Task.CompletedTask
            : RunPlatformActionAsync(
                () => _platformService.OpenFileAsync(SelectedDownload.DestinationPath),
                "Opened downloaded file.");

    [RelayCommand]
    private Task RevealSelectedDestinationAsync()
        => SelectedDownload is null
            ? Task.CompletedTask
            : RunPlatformActionAsync(
                () => _platformService.RevealFileAsync(SelectedDownload.DestinationPath),
                "Opened destination folder.");

    [RelayCommand]
    private Task OpenSelectedSourceAsync()
        => SelectedDownload is null
            ? Task.CompletedTask
            : RunPlatformActionAsync(
                () => _platformService.OpenUriAsync(SelectedDownload.Source),
                "Opened download URL.");

    [RelayCommand]
    private Task OpenSelectedSourcePageAsync()
        => SelectedDownload?.SourcePage is not Uri sourcePage
            ? Task.CompletedTask
            : RunPlatformActionAsync(
                () => _platformService.OpenUriAsync(sourcePage),
                "Opened source page.");

    [RelayCommand]
    private async Task ExportVisibleHistoryAsync()
    {
        if (string.IsNullOrWhiteSpace(HistoryTransferPath))
        {
            HistoryTransferStatus = "Choose an export file first.";
            return;
        }

        HashSet<string> visibleIds = FilteredDownloads
            .Select(static item => item.Id)
            .ToHashSet(StringComparer.Ordinal);
        DownloadSnapshot[] downloads = _applicationState.Current.Downloads
            .Where(item => visibleIds.Contains(item.Id))
            .ToArray();
        try
        {
            await _downloadListTransferService.ExportAsync(HistoryTransferPath, downloads);
            HistoryTransferStatus = $"Exported {downloads.Length:N0} download entries without credentials or cookies.";
        }
        catch (IOException exception)
        {
            HistoryTransferStatus = exception.Message;
        }
        catch (UnauthorizedAccessException exception)
        {
            HistoryTransferStatus = exception.Message;
        }
        catch (InvalidDataException exception)
        {
            HistoryTransferStatus = exception.Message;
        }
    }

    [RelayCommand]
    private async Task ImportDownloadListAsync()
    {
        if (string.IsNullOrWhiteSpace(HistoryTransferPath))
        {
            HistoryTransferStatus = "Choose a download-list file first.";
            return;
        }

        try
        {
            DownloadListImportResult result = await _downloadListTransferService.ImportAsync(HistoryTransferPath);
            int added = 0;
            int failed = 0;
            foreach (DownloadListEntry entry in result.Downloads)
            {
                string destination = string.IsNullOrWhiteSpace(entry.DestinationDirectory)
                    ? DestinationFolder
                    : entry.DestinationDirectory;
                try
                {
                    await _downloadManager.AddAsync(new DownloadRequest(
                        entry.Source,
                        destination,
                        entry.FileName,
                        QueueId: entry.QueueId,
                        CategoryId: entry.CategoryId,
                        DuplicateBehavior: DuplicateFileBehavior.AutoRename,
                        ConnectionCount: entry.ConnectionCount,
                        Priority: entry.Priority,
                        SourcePage: entry.SourcePage,
                        Mirrors: entry.Mirrors,
                        ExpectedChecksumAlgorithm: entry.ExpectedChecksumAlgorithm,
                        ExpectedChecksum: entry.ExpectedChecksum,
                        ExpectedLength: entry.ExpectedLength,
                        BackendPreference: entry.BackendPreference,
                        AllowBackendFallback: entry.AllowBackendFallback,
                        Tags: entry.Tags));
                    added++;
                }
                catch (ArgumentException)
                {
                    failed++;
                }
                catch (IOException)
                {
                    failed++;
                }
                catch (UnauthorizedAccessException)
                {
                    failed++;
                }
            }

            HistoryTransferStatus = $"Imported {added:N0} download(s) from {result.SourceFormat}; ignored {result.IgnoredEntries:N0}; failed to queue {failed:N0}.";
        }
        catch (System.Text.Json.JsonException exception)
        {
            HistoryTransferStatus = $"Invalid JSON: {exception.Message}";
        }
        catch (InvalidDataException exception)
        {
            HistoryTransferStatus = exception.Message;
        }
        catch (IOException exception)
        {
            HistoryTransferStatus = exception.Message;
        }
        catch (UnauthorizedAccessException exception)
        {
            HistoryTransferStatus = exception.Message;
        }
    }

    [RelayCommand]
    private async Task PruneHistoryAsync()
    {
        try
        {
            int removed = await _downloadManager.PruneHistoryAsync();
            OperationMessage = removed == 0
                ? "No history entries matched the retention policy."
                : $"Pruned {removed:N0} history entries; downloaded files were preserved.";
        }
        catch (IOException exception)
        {
            OperationMessage = exception.Message;
        }
        catch (UnauthorizedAccessException exception)
        {
            OperationMessage = exception.Message;
        }
    }

    private HistoryRetentionSettings BuildHistoryRetentionSettings()
    {
        HistoryRetentionSettings current = _settingsService.Current.History ?? HistoryRetentionSettings.Default;
        int days = ParseInteger(HistoryRetentionDays, current.RetentionDays);
        int maximumEntries = ParseInteger(HistoryMaximumEntries, current.MaximumEntries);
        return new HistoryRetentionSettings(HistoryRetentionEnabled, days, maximumEntries).Normalize();
    }

    private void ApplyHistorySettings(ApplicationSettings settings)
    {
        HistoryRetentionSettings history = settings.History ?? HistoryRetentionSettings.Default;
        HistoryRetentionEnabled = history.Enabled;
        HistoryRetentionDays = history.RetentionDays.ToString(System.Globalization.CultureInfo.InvariantCulture);
        HistoryMaximumEntries = history.MaximumEntries.ToString(System.Globalization.CultureInfo.InvariantCulture);
    }

    private void ApplySelectedHistoryItem(DownloadItemViewModel? item)
    {
        RefreshedDownloadUrl = item?.Source.AbsoluteUri ?? string.Empty;
        RefreshedSourcePage = item?.SourcePage?.AbsoluteUri ?? string.Empty;
        RelocationDestinationPath = item?.DestinationPath ?? string.Empty;
    }

    private static Uri? ParseOptionalHttpUri(string? value)
    {
        if (!Uri.TryCreate(value, UriKind.Absolute, out Uri? uri)
            || uri.Scheme is not ("http" or "https"))
        {
            return null;
        }

        return uri;
    }

    private async Task RunPlatformActionAsync(Func<Task> action, string successMessage)
    {
        try
        {
            await action();
            OperationMessage = successMessage;
        }
        catch (IOException exception)
        {
            OperationMessage = exception.Message;
        }
        catch (UnauthorizedAccessException exception)
        {
            OperationMessage = exception.Message;
        }
        catch (InvalidOperationException exception)
        {
            OperationMessage = exception.Message;
        }
        catch (ArgumentException exception)
        {
            OperationMessage = exception.Message;
        }
    }
}
