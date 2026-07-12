using Avalonia.Controls;
using Avalonia.Interactivity;
using XDM.App.ViewModels;

namespace XDM.App.Views;

public partial class SettingsView : UserControl
{
    public SettingsView()
    {
        InitializeComponent();
    }

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

    private async void BrowseAria2Executable_Click(object? sender, RoutedEventArgs e)
    {
        string? path = await StoragePickerHelper.PickFileAsync(
            this,
            Localize("picker_aria2_executable", "Choose aria2c executable"));
        if (!string.IsNullOrWhiteSpace(path) && ViewModel is { } viewModel)
        {
            viewModel.Aria2ExecutablePath = path;
        }
    }

    private async void BrowseAria2SessionFile_Click(object? sender, RoutedEventArgs e)
    {
        string? path = await StoragePickerHelper.SaveFileAsync(
            this,
            Localize("picker_aria2_session", "Choose aria2 session file"),
            "aria2.session");
        if (!string.IsNullOrWhiteSpace(path) && ViewModel is { } viewModel)
        {
            viewModel.Aria2SessionFilePath = path;
        }
    }

    private async void BrowseAria2Destination_Click(object? sender, RoutedEventArgs e)
    {
        string? path = await StoragePickerHelper.PickFolderAsync(
            this,
            Localize("picker_aria2_destination", "Choose aria2 download destination"));
        if (!string.IsNullOrWhiteSpace(path) && ViewModel is { } viewModel)
        {
            viewModel.Aria2DestinationFolder = path;
        }
    }

    private async void BrowseSettingsImport_Click(object? sender, RoutedEventArgs e)
    {
        string? path = await StoragePickerHelper.PickFileAsync(
            this,
            Localize("picker_settings_import", "Choose modern or legacy XDM settings"));
        if (!string.IsNullOrWhiteSpace(path) && ViewModel is { } viewModel)
        {
            viewModel.SettingsTransferPath = path;
        }
    }

    private async void BrowseSettingsDirectory_Click(object? sender, RoutedEventArgs e)
    {
        string? path = await StoragePickerHelper.PickFolderAsync(
            this,
            Localize("picker_settings_directory", "Choose legacy XDM settings directory"));
        if (!string.IsNullOrWhiteSpace(path) && ViewModel is { } viewModel)
        {
            viewModel.SettingsTransferPath = path;
        }
    }

    private async void BrowseSettingsExport_Click(object? sender, RoutedEventArgs e)
    {
        string? path = await StoragePickerHelper.SaveFileAsync(
            this,
            Localize("picker_settings_export", "Export XDM settings"),
            "xdm-settings.json",
            "json");
        if (!string.IsNullOrWhiteSpace(path) && ViewModel is { } viewModel)
        {
            viewModel.SettingsTransferPath = path;
        }
    }
}
