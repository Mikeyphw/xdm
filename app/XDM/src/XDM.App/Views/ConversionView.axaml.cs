using Avalonia.Controls;
using Avalonia.Interactivity;
using XDM.App.ViewModels;

namespace XDM.App.Views;

public partial class ConversionView : UserControl
{
    public ConversionView()
    {
        InitializeComponent();
        SizeChanged += (_, _) => ApplyResponsiveLayout(Bounds.Width);
    }

    internal void ApplyResponsiveLayout(double width)
    {
        bool compact = width > 0 && width < 920;
        SetClass("compact-conversion", compact);

        ConversionWorkspace.ColumnDefinitions.Clear();
        ConversionWorkspace.RowDefinitions.Clear();
        if (compact)
        {
            ConversionWorkspace.ColumnDefinitions.Add(new ColumnDefinition(GridLength.Star));
            ConversionWorkspace.RowDefinitions.Add(new RowDefinition(GridLength.Auto));
            ConversionWorkspace.RowDefinitions.Add(new RowDefinition(GridLength.Auto));
            Grid.SetColumn(ConversionJobsPane, 0);
            Grid.SetRow(ConversionJobsPane, 0);
            Grid.SetColumn(ConversionDetailsPane, 0);
            Grid.SetRow(ConversionDetailsPane, 1);
            return;
        }

        ConversionWorkspace.ColumnDefinitions.Add(new ColumnDefinition(new GridLength(2, GridUnitType.Star)));
        ConversionWorkspace.ColumnDefinitions.Add(new ColumnDefinition(GridLength.Star));
        ConversionWorkspace.RowDefinitions.Add(new RowDefinition(GridLength.Auto));
        Grid.SetColumn(ConversionJobsPane, 0);
        Grid.SetRow(ConversionJobsPane, 0);
        Grid.SetColumn(ConversionDetailsPane, 1);
        Grid.SetRow(ConversionDetailsPane, 0);
    }

    private void SetClass(string className, bool enabled)
    {
        if (enabled)
        {
            if (!Classes.Contains(className))
            {
                Classes.Add(className);
            }
            return;
        }

        Classes.Remove(className);
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
