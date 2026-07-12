using Avalonia.Controls;
using Avalonia.Interactivity;
using XDM.App.ViewModels;

namespace XDM.App.Views;

public partial class DownloadsView : UserControl
{
    public DownloadsView()
    {
        InitializeComponent();
    }

    public void FocusNewDownload() => NewDownloadUrlsInput.Focus();

    public void FocusSearch() => DownloadSearchInput.Focus();

    private MainWindowViewModel? ViewModel => DataContext as MainWindowViewModel;

    private string Localize(string key, string fallback)
        => ViewModel?.Localization.Get(key, fallback) ?? fallback;

    private async void BrowseDestination_Click(object? sender, RoutedEventArgs e)
    {
        string? path = await StoragePickerHelper.PickFolderAsync(
            this,
            Localize("picker_download_destination", "Choose download destination"));
        if (!string.IsNullOrWhiteSpace(path) && ViewModel is { } viewModel)
        {
            viewModel.DestinationFolder = path;
        }
    }

    private async void BrowseRelocationDestination_Click(object? sender, RoutedEventArgs e)
    {
        string suggestedName = ViewModel?.SelectedDownload?.FileName ?? "download.bin";
        string? path = await StoragePickerHelper.SaveFileAsync(
            this,
            Localize("picker_relocation_destination", "Choose new download path"),
            suggestedName);
        if (!string.IsNullOrWhiteSpace(path) && ViewModel is { } viewModel)
        {
            viewModel.RelocationDestinationPath = path;
        }
    }

    private async void BrowseHistoryImport_Click(object? sender, RoutedEventArgs e)
    {
        string? path = await StoragePickerHelper.PickFileAsync(
            this,
            Localize("picker_history_import", "Choose XDM download list or plain URL list"));
        if (!string.IsNullOrWhiteSpace(path) && ViewModel is { } viewModel)
        {
            viewModel.HistoryTransferPath = path;
        }
    }

    private async void BrowseHistoryExport_Click(object? sender, RoutedEventArgs e)
    {
        string? path = await StoragePickerHelper.SaveFileAsync(
            this,
            Localize("picker_history_export", "Export XDM download list"),
            "xdm-downloads.json",
            "json");
        if (!string.IsNullOrWhiteSpace(path) && ViewModel is { } viewModel)
        {
            viewModel.HistoryTransferPath = path;
        }
    }
}
