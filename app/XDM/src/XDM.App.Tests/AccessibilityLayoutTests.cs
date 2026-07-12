using Avalonia.Automation;
using Avalonia.Controls;
using Avalonia.Headless.XUnit;
using Avalonia.LogicalTree;
using XDM.App;
using XDM.App.Views;
using Xunit;

namespace XDM.App.Tests;

public sealed class AccessibilityLayoutTests
{
    [AvaloniaFact]
    public void MainWindowLoadsAllPageViewsAtMinimumSize()
    {
        MainWindow window = new();
        window.ApplyResponsiveShell(760);

        Assert.Single(window.GetLogicalDescendants().OfType<DownloadsView>());
        Assert.Single(window.GetLogicalDescendants().OfType<QueuesView>());
        Assert.Single(window.GetLogicalDescendants().OfType<SchedulerView>());
        Assert.Single(window.GetLogicalDescendants().OfType<BrowserIntegrationView>());
        Assert.Single(window.GetLogicalDescendants().OfType<MediaView>());
        Assert.Single(window.GetLogicalDescendants().OfType<ConversionView>());
        Assert.Single(window.GetLogicalDescendants().OfType<SettingsView>());
        Assert.Single(window.GetLogicalDescendants().OfType<DiagnosticsView>());
    }

    [AvaloniaFact]
    public void MainWindowUsesOverlayNavigationAtNarrowWidth()
    {
        MainWindow window = new();
        window.ApplyResponsiveShell(800);
        SplitView splitView = Assert.IsType<SplitView>(window.FindControl<SplitView>("NavigationSplitView"));

        Assert.Equal(SplitViewDisplayMode.Overlay, splitView.DisplayMode);
        Assert.False(splitView.IsPaneOpen);
    }


    [AvaloniaFact]
    public void MainWindowUsesClosedCompactInlineNavigationAtMediumWidth()
    {
        MainWindow window = new();
        window.ApplyResponsiveShell(1024);
        SplitView splitView = Assert.IsType<SplitView>(window.FindControl<SplitView>("NavigationSplitView"));

        Assert.Equal(SplitViewDisplayMode.CompactInline, splitView.DisplayMode);
        Assert.False(splitView.IsPaneOpen);
    }

    [AvaloniaFact]
    public void MainWindowUsesOpenCompactInlineNavigationAtWideWidth()
    {
        MainWindow window = new();
        window.ApplyResponsiveShell(1280);
        SplitView splitView = Assert.IsType<SplitView>(window.FindControl<SplitView>("NavigationSplitView"));

        Assert.Equal(SplitViewDisplayMode.CompactInline, splitView.DisplayMode);
        Assert.True(splitView.IsPaneOpen);
    }

    [AvaloniaFact]
    public void DownloadsViewPrimaryInputsAcceptProgrammaticFocus()
    {
        DownloadsView view = new();
        Window window = new() { Width = 900, Height = 700, Content = view };
        window.Show();

        view.FocusNewDownload();
        TextBox newDownload = Assert.IsType<TextBox>(view.FindControl<TextBox>("NewDownloadUrlsInput"));
        Assert.True(newDownload.IsFocused);

        view.FocusSearch();
        TextBox search = Assert.IsType<TextBox>(view.FindControl<TextBox>("DownloadSearchInput"));
        Assert.True(search.IsFocused);
    }

    [AvaloniaFact]
    public void RuntimeAutomationIdsAreUnique()
    {
        MainWindow window = new();
        string[] automationIds = window.GetLogicalDescendants()
            .OfType<Control>()
            .Select(static control => AutomationProperties.GetAutomationId(control))
            .OfType<string>()
            .Where(static id => !string.IsNullOrWhiteSpace(id))
            .ToArray();

        Assert.NotEmpty(automationIds);
        Assert.Equal(
            automationIds.Length,
            automationIds.Distinct(StringComparer.Ordinal).Count());
    }

    [AvaloniaFact]
    public void DownloadsViewReflowsFormsAtCompactWidth()
    {
        DownloadsView view = new();
        view.ApplyResponsiveLayout(800);

        Grid newDownloadGrid = Assert.IsType<Grid>(view.FindControl<Grid>("NewDownloadGrid"));
        Grid optionsGrid = Assert.IsType<Grid>(view.FindControl<Grid>("DownloadOptionsGrid"));
        Grid searchGrid = Assert.IsType<Grid>(view.FindControl<Grid>("DownloadSearchGrid"));
        Grid workspace = Assert.IsType<Grid>(view.FindControl<Grid>("DownloadWorkspace"));
        Control destination = Assert.IsAssignableFrom<Control>(view.FindControl<StackPanel>("DownloadDestinationField"));

        Assert.Contains("compact-downloads", view.Classes);
        Assert.Single(newDownloadGrid.ColumnDefinitions);
        Assert.Equal(3, newDownloadGrid.RowDefinitions.Count);
        Assert.Equal(1, Grid.GetRow(destination));
        Assert.Equal(2, optionsGrid.ColumnDefinitions.Count);
        Assert.Equal(2, optionsGrid.RowDefinitions.Count);
        Assert.Equal(2, searchGrid.ColumnDefinitions.Count);
        Assert.Equal(3, workspace.ColumnDefinitions.Count);
        Assert.Equal(3d, workspace.ColumnDefinitions[0].Width.Value);
        Assert.Equal(2d, workspace.ColumnDefinitions[2].Width.Value);
    }

    [AvaloniaFact]
    public void DownloadsViewRestoresWideLayout()
    {
        DownloadsView view = new();
        view.ApplyResponsiveLayout(1280);

        Grid newDownloadGrid = Assert.IsType<Grid>(view.FindControl<Grid>("NewDownloadGrid"));
        Grid optionsGrid = Assert.IsType<Grid>(view.FindControl<Grid>("DownloadOptionsGrid"));
        Grid searchGrid = Assert.IsType<Grid>(view.FindControl<Grid>("DownloadSearchGrid"));
        Grid workspace = Assert.IsType<Grid>(view.FindControl<Grid>("DownloadWorkspace"));

        Assert.DoesNotContain("compact-downloads", view.Classes);
        Assert.Equal(3, newDownloadGrid.ColumnDefinitions.Count);
        Assert.Single(newDownloadGrid.RowDefinitions);
        Assert.Equal(4, optionsGrid.ColumnDefinitions.Count);
        Assert.Single(optionsGrid.RowDefinitions);
        Assert.Equal(3, searchGrid.ColumnDefinitions.Count);
        Assert.Equal(2d, workspace.ColumnDefinitions[0].Width.Value);
        Assert.Equal(1d, workspace.ColumnDefinitions[2].Width.Value);
    }

    [AvaloniaFact]
    public void MainWindowExposesRuntimeStatusAndCompactContentState()
    {
        MainWindow window = new();
        window.ApplyResponsiveShell(800);

        Assert.Contains("compact-content", window.Classes);
        Assert.NotNull(window.FindControl<Border>("OperationStatusBanner"));
        Border sectionIcon = Assert.IsType<Border>(window.FindControl<Border>("SectionIcon"));
        Assert.False(sectionIcon.IsVisible);
    }

}
