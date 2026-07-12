using Avalonia.Controls;
using Avalonia.Interactivity;
using XDM.App.ViewModels;

namespace XDM.App.Views;

public partial class MediaView : UserControl
{
    public MediaView()
    {
        InitializeComponent();
    }

    private async void BrowseDestination_Click(object? sender, RoutedEventArgs e)
    {
        MainWindowViewModel? viewModel = DataContext as MainWindowViewModel;
        string title = viewModel?.Localization.Get("picker_download_destination", "Choose download destination")
            ?? "Choose download destination";
        string? path = await StoragePickerHelper.PickFolderAsync(this, title);
        if (!string.IsNullOrWhiteSpace(path) && viewModel is not null)
        {
            viewModel.DestinationFolder = path;
        }
    }
}
