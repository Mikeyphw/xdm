using Avalonia.Controls;
using Avalonia.Interactivity;
using XDM.App.ViewModels;

namespace XDM.App.Views;

public partial class MediaView : UserControl
{
    public MediaView()
    {
        InitializeComponent();
        SizeChanged += (_, _) => ApplyResponsiveLayout(Bounds.Width);
    }

    internal void ApplyResponsiveLayout(double width)
    {
        bool compact = width > 0 && width < 920;
        SetClass("compact-media", compact);

        ConfigureWorkspace(compact);
        ConfigureThreeFieldGrid(MediaPreferenceGrid, [MediaQualityField, MediaAudioLanguageField, MediaSubtitleLanguageField], compact);
        ConfigureTwoFieldGrid(MediaExactFormatGrid, MediaExactVideoField, MediaExactAudioField, compact);
        ConfigureTwoFieldGrid(MediaLiveLimitGrid, MediaLiveDurationField, MediaLiveMaximumSizeField, compact);

        MediaVariantHeaderGrid.ColumnDefinitions.Clear();
        string[] widths = compact
            ? ["44", "*", "*", "*", "*", "*", "*"]
            : ["42", "80", "100", "92", "100", "88", "*"];
        foreach (string widthValue in widths)
        {
            MediaVariantHeaderGrid.ColumnDefinitions.Add(new ColumnDefinition(GridLength.Parse(widthValue)));
        }
    }

    private void ConfigureWorkspace(bool compact)
    {
        MediaWorkspace.ColumnDefinitions.Clear();
        MediaWorkspace.RowDefinitions.Clear();
        if (compact)
        {
            MediaWorkspace.ColumnDefinitions.Add(new ColumnDefinition(GridLength.Star));
            MediaWorkspace.RowDefinitions.Add(new RowDefinition(GridLength.Auto));
            MediaWorkspace.RowDefinitions.Add(new RowDefinition(GridLength.Auto));
            Grid.SetColumn(MediaInboxPane, 0);
            Grid.SetRow(MediaInboxPane, 0);
            MediaWorkspaceSplitter.IsVisible = false;
            Grid.SetColumn(MediaDetailsPane, 0);
            Grid.SetRow(MediaDetailsPane, 1);
            return;
        }

        MediaWorkspace.ColumnDefinitions.Add(new ColumnDefinition(new GridLength(280)));
        MediaWorkspace.ColumnDefinitions.Add(new ColumnDefinition(new GridLength(8)));
        MediaWorkspace.ColumnDefinitions.Add(new ColumnDefinition(GridLength.Star));
        MediaWorkspace.RowDefinitions.Add(new RowDefinition(GridLength.Star));
        Grid.SetColumn(MediaInboxPane, 0);
        Grid.SetRow(MediaInboxPane, 0);
        MediaWorkspaceSplitter.IsVisible = true;
        Grid.SetColumn(MediaDetailsPane, 2);
        Grid.SetRow(MediaDetailsPane, 0);
    }

    private static void ConfigureThreeFieldGrid(Grid grid, IReadOnlyList<Control> fields, bool compact)
    {
        grid.ColumnDefinitions.Clear();
        grid.RowDefinitions.Clear();
        int columns = compact ? 1 : 3;
        for (int index = 0; index < columns; index++)
        {
            grid.ColumnDefinitions.Add(new ColumnDefinition(GridLength.Star));
        }
        for (int index = 0; index < Math.Ceiling(fields.Count / (double)columns); index++)
        {
            grid.RowDefinitions.Add(new RowDefinition(GridLength.Auto));
        }
        for (int index = 0; index < fields.Count; index++)
        {
            Grid.SetColumn(fields[index], index % columns);
            Grid.SetRow(fields[index], index / columns);
        }
    }

    private static void ConfigureTwoFieldGrid(Grid grid, Control first, Control second, bool compact)
    {
        grid.ColumnDefinitions.Clear();
        grid.RowDefinitions.Clear();
        grid.ColumnDefinitions.Add(new ColumnDefinition(GridLength.Star));
        if (compact)
        {
            grid.RowDefinitions.Add(new RowDefinition(GridLength.Auto));
            grid.RowDefinitions.Add(new RowDefinition(GridLength.Auto));
            Grid.SetColumn(first, 0);
            Grid.SetRow(first, 0);
            Grid.SetColumn(second, 0);
            Grid.SetRow(second, 1);
            return;
        }

        grid.ColumnDefinitions.Add(new ColumnDefinition(GridLength.Star));
        grid.RowDefinitions.Add(new RowDefinition(GridLength.Auto));
        Grid.SetColumn(first, 0);
        Grid.SetRow(first, 0);
        Grid.SetColumn(second, 1);
        Grid.SetRow(second, 0);
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
