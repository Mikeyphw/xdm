using Avalonia.Controls;
using Avalonia.Interactivity;
using XDM.App.ViewModels;

namespace XDM.App.Views;

public partial class ConversionView : UserControl
{
    public ConversionView()
    {
        InitializeComponent();
    }

    private async void BrowseConversionSource_Click(object? sender, RoutedEventArgs e)
    {
        MainWindowViewModel? viewModel = DataContext as MainWindowViewModel;
        string title = viewModel?.Localization.Get("picker_conversion_source", "Choose media to convert")
            ?? "Choose media to convert";
        string? path = await StoragePickerHelper.PickFileAsync(this, title);
        if (!string.IsNullOrWhiteSpace(path) && viewModel is not null)
        {
            viewModel.SetConversionSourcePath(path);
        }
    }
}
