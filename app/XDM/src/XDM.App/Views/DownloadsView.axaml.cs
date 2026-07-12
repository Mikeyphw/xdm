using Avalonia.Controls;
using Avalonia.Interactivity;
using XDM.App.ViewModels;

namespace XDM.App.Views;

public partial class DownloadsView : UserControl
{
    public DownloadsView()
    {
        InitializeComponent();
        SizeChanged += (_, _) => ApplyResponsiveLayout(Bounds.Width);
    }

    internal void ApplyResponsiveLayout(double width)
    {
        bool compact = width > 0 && width < 920;
        SetClass("compact-downloads", compact);

        ConfigureNewDownloadGrid(compact);
        ConfigureDownloadOptionsGrid(compact);
        ConfigureSearchGrid(compact);
        ConfigureWorkspace(compact);
    }

    public void FocusNewDownload() => NewDownloadUrlsInput.Focus();

    public void FocusSearch() => DownloadSearchInput.Focus();

    private void ConfigureNewDownloadGrid(bool compact)
    {
        NewDownloadGrid.ColumnDefinitions.Clear();
        NewDownloadGrid.RowDefinitions.Clear();
        if (compact)
        {
            NewDownloadGrid.ColumnDefinitions.Add(new ColumnDefinition(GridLength.Star));
            NewDownloadGrid.RowDefinitions.Add(new RowDefinition(GridLength.Auto));
            NewDownloadGrid.RowDefinitions.Add(new RowDefinition(GridLength.Auto));
            NewDownloadGrid.RowDefinitions.Add(new RowDefinition(GridLength.Auto));
            Grid.SetRow(DownloadUrlField, 0);
            Grid.SetColumn(DownloadUrlField, 0);
            Grid.SetRow(DownloadDestinationField, 1);
            Grid.SetColumn(DownloadDestinationField, 0);
            Grid.SetRow(NewDownloadActions, 2);
            Grid.SetColumn(NewDownloadActions, 0);
            NewDownloadActions.HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Right;
            return;
        }

        NewDownloadGrid.ColumnDefinitions.Add(new ColumnDefinition(new GridLength(2, GridUnitType.Star)));
        NewDownloadGrid.ColumnDefinitions.Add(new ColumnDefinition(GridLength.Star));
        NewDownloadGrid.ColumnDefinitions.Add(new ColumnDefinition(GridLength.Auto));
        NewDownloadGrid.RowDefinitions.Add(new RowDefinition(GridLength.Auto));
        Grid.SetRow(DownloadUrlField, 0);
        Grid.SetColumn(DownloadUrlField, 0);
        Grid.SetRow(DownloadDestinationField, 0);
        Grid.SetColumn(DownloadDestinationField, 1);
        Grid.SetRow(NewDownloadActions, 0);
        Grid.SetColumn(NewDownloadActions, 2);
        NewDownloadActions.HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Stretch;
    }

    private void ConfigureDownloadOptionsGrid(bool compact)
    {
        DownloadOptionsGrid.ColumnDefinitions.Clear();
        DownloadOptionsGrid.RowDefinitions.Clear();
        int columns = compact ? 2 : 4;
        for (int index = 0; index < columns; index++)
        {
            DownloadOptionsGrid.ColumnDefinitions.Add(new ColumnDefinition(GridLength.Star));
        }
        DownloadOptionsGrid.RowDefinitions.Add(new RowDefinition(GridLength.Auto));
        if (compact)
        {
            DownloadOptionsGrid.RowDefinitions.Add(new RowDefinition(GridLength.Auto));
        }

        Control[] fields = [CategoryField, QueueField, DuplicateBehaviorField, SpeedLimitField];
        for (int index = 0; index < fields.Length; index++)
        {
            Grid.SetColumn(fields[index], index % columns);
            Grid.SetRow(fields[index], index / columns);
        }
    }

    private void ConfigureSearchGrid(bool compact)
    {
        DownloadSearchGrid.ColumnDefinitions.Clear();
        DownloadSearchGrid.RowDefinitions.Clear();
        if (compact)
        {
            DownloadSearchGrid.ColumnDefinitions.Add(new ColumnDefinition(GridLength.Star));
            DownloadSearchGrid.ColumnDefinitions.Add(new ColumnDefinition(GridLength.Auto));
            DownloadSearchGrid.RowDefinitions.Add(new RowDefinition(GridLength.Auto));
            DownloadSearchGrid.RowDefinitions.Add(new RowDefinition(GridLength.Auto));
            Grid.SetColumn(DownloadSearchInput, 0);
            Grid.SetColumnSpan(DownloadSearchInput, 2);
            Grid.SetRow(DownloadSearchInput, 0);
            Grid.SetColumn(DownloadStatusFilter, 0);
            Grid.SetRow(DownloadStatusFilter, 1);
            Grid.SetColumn(SelectVisibleDownloads, 1);
            Grid.SetRow(SelectVisibleDownloads, 1);
            return;
        }

        DownloadSearchGrid.ColumnDefinitions.Add(new ColumnDefinition(GridLength.Star));
        DownloadSearchGrid.ColumnDefinitions.Add(new ColumnDefinition(new GridLength(220)));
        DownloadSearchGrid.ColumnDefinitions.Add(new ColumnDefinition(GridLength.Auto));
        DownloadSearchGrid.RowDefinitions.Add(new RowDefinition(GridLength.Auto));
        Grid.SetColumn(DownloadSearchInput, 0);
        Grid.SetColumnSpan(DownloadSearchInput, 1);
        Grid.SetRow(DownloadSearchInput, 0);
        Grid.SetColumn(DownloadStatusFilter, 1);
        Grid.SetRow(DownloadStatusFilter, 0);
        Grid.SetColumn(SelectVisibleDownloads, 2);
        Grid.SetRow(SelectVisibleDownloads, 0);
    }

    private void ConfigureWorkspace(bool compact)
    {
        DownloadWorkspace.ColumnDefinitions.Clear();
        DownloadWorkspace.RowDefinitions.Clear();
        DownloadWorkspace.ColumnDefinitions.Add(new ColumnDefinition(
            new GridLength(compact ? 3 : 2, GridUnitType.Star)));
        DownloadWorkspace.ColumnDefinitions.Add(new ColumnDefinition(new GridLength(6)));
        DownloadWorkspace.ColumnDefinitions.Add(new ColumnDefinition(
            new GridLength(compact ? 2 : 1, GridUnitType.Star)));
        DownloadWorkspace.RowDefinitions.Add(new RowDefinition(GridLength.Star));
        Grid.SetColumn(DownloadListPane, 0);
        Grid.SetRow(DownloadListPane, 0);
        Grid.SetColumn(DownloadWorkspaceSplitter, 1);
        Grid.SetRow(DownloadWorkspaceSplitter, 0);
        Grid.SetColumn(DownloadDetailsPane, 2);
        Grid.SetRow(DownloadDetailsPane, 0);
        DownloadWorkspaceSplitter.ResizeDirection = GridResizeDirection.Columns;
        DownloadWorkspaceSplitter.Width = 6;
        DownloadWorkspaceSplitter.Height = double.NaN;
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

    private void FocusNewDownload_Click(object? sender, RoutedEventArgs e)
        => FocusNewDownload();

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

    private async void ImportMetalink_Click(object? sender, RoutedEventArgs e)
    {
        string? path = await StoragePickerHelper.PickFileAsync(
            this,
            Localize("picker_metalink_import", "Choose a Metalink file"));
        if (!string.IsNullOrWhiteSpace(path) && ViewModel is { } viewModel)
        {
            await viewModel.ImportMetalinkAsync(path);
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
