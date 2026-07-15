using Avalonia.Automation;
using Avalonia.Controls;
using Avalonia.Headless.XUnit;
using Avalonia.LogicalTree;
using System.Text.RegularExpressions;
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
        Assert.Single(window.GetLogicalDescendants().OfType<RecoveryView>());
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
    public void RuntimeExposesExactlyOneMainLandmark()
    {
        MainWindow window = new();

        Control[] mainLandmarks = window.GetLogicalDescendants()
            .OfType<Control>()
            .Where(static control => string.Equals(
                AutomationProperties.GetLandmarkType(control)?.ToString(),
                "Main",
                StringComparison.Ordinal))
            .ToArray();

        Control main = Assert.Single(mainLandmarks);
        Assert.Equal("PageContentHost", main.Name);
    }

    [AvaloniaFact]
    public void HeaderUtilityControlsHaveAccessibleNamesAndStableIds()
    {
        MainWindow window = new();
        window.Show();

        Button commandPalette = Assert.IsType<Button>(window.FindControl<Button>("CommandPaletteButton"));
        Button notificationCenter = Assert.IsType<Button>(window.FindControl<Button>("NotificationCenterButton"));

        Assert.Equal("CommandPaletteButton", AutomationProperties.GetAutomationId(commandPalette));
        Assert.Equal("NotificationCenterButton", AutomationProperties.GetAutomationId(notificationCenter));
        Assert.False(string.IsNullOrWhiteSpace(AutomationProperties.GetName(commandPalette)));
        Assert.False(string.IsNullOrWhiteSpace(AutomationProperties.GetName(notificationCenter)));
        Assert.Equal("Ctrl+K", AutomationProperties.GetAcceleratorKey(commandPalette));
    }

    [Fact]
    public void DesktopXamlDoesNotDeclareHostedPageMainLandmarks()
    {
        string viewsDirectory = Path.GetFullPath(Path.Combine(
            AppContext.BaseDirectory,
            "../../../../XDM.App/Views"));
        string[] offenders = Directory.EnumerateFiles(viewsDirectory, "*.axaml")
            .Where(static file => File.ReadAllText(file).Contains("AutomationProperties.LandmarkType=\"Main\"", StringComparison.Ordinal))
            .Select(Path.GetFileName)
            .ToArray()!;

        Assert.Empty(offenders);
    }

    [Fact]
    public void DesktopDynamicResourcesAreDefinedByShellResources()
    {
        string appDirectory = Path.GetFullPath(Path.Combine(
            AppContext.BaseDirectory,
            "../../../../XDM.App"));
        string[] xamlFiles = Directory.EnumerateFiles(appDirectory, "*.axaml", SearchOption.AllDirectories).ToArray();
        Regex dynamicResourcePattern = new(@"DynamicResource\s+([A-Za-z0-9_]+)", RegexOptions.Compiled);
        Regex resourceKeyPattern = new(@"x:Key=""([^""]+)""", RegexOptions.Compiled);

        HashSet<string> references = xamlFiles
            .SelectMany(file => dynamicResourcePattern.Matches(File.ReadAllText(file)).Select(match => match.Groups[1].Value))
            .Where(static key => key.StartsWith("Xdm", StringComparison.Ordinal))
            .ToHashSet(StringComparer.Ordinal);
        HashSet<string> definitions = xamlFiles
            .SelectMany(file => resourceKeyPattern.Matches(File.ReadAllText(file)).Select(match => match.Groups[1].Value))
            .ToHashSet(StringComparer.Ordinal);

        string[] missing = references.Except(definitions, StringComparer.Ordinal).Order(StringComparer.Ordinal).ToArray();

        Assert.Empty(missing);
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
        Assert.Equal(GridUnitType.Pixel, workspace.ColumnDefinitions[2].Width.GridUnitType);
        Assert.Equal(360d, workspace.ColumnDefinitions[2].Width.Value);
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

    [AvaloniaFact]
    public void DesktopProductivityControlsAreAvailable()
    {
        MainWindow window = new();
        DownloadsView downloads = Assert.Single(window.GetLogicalDescendants().OfType<DownloadsView>());

        Assert.NotNull(window.FindControl<Button>("CommandPaletteButton"));
        Assert.NotNull(window.FindControl<Button>("NotificationCenterButton"));
        ListBox list = Assert.IsType<ListBox>(downloads.FindControl<ListBox>("DownloadsList"));
        Assert.True(list.SelectionMode.HasFlag(SelectionMode.Multiple));
        Assert.NotNull(downloads.FindControl<GridSplitter>("DownloadWorkspaceSplitter"));
    }

    [AvaloniaFact]
    public void MediaViewExposesInboxQualityAndLiveLimitControls()
    {
        MediaView view = new();

        Assert.NotNull(view.FindControl<ListBox>("MediaInboxList"));
        Assert.NotNull(view.FindControl<ComboBox>("MediaQualitySelector"));
        Assert.NotNull(view.FindControl<NumericUpDown>("MediaLiveMaximumSizeInput"));
    }

    [AvaloniaFact]
    public void DenseDesktopPagesReflowAtCompactWidth()
    {
        MediaView media = new();
        media.ApplyResponsiveLayout(760);
        Grid mediaWorkspace = Assert.IsType<Grid>(media.FindControl<Grid>("MediaWorkspace"));
        Assert.Contains("compact-media", media.Classes);
        Assert.Single(mediaWorkspace.ColumnDefinitions);
        Assert.Equal(2, mediaWorkspace.RowDefinitions.Count);

        SchedulerView scheduler = new();
        scheduler.ApplyResponsiveLayout(760);
        Grid schedulerWorkspace = Assert.IsType<Grid>(scheduler.FindControl<Grid>("SchedulerWorkspace"));
        Assert.Contains("compact-scheduler", scheduler.Classes);
        Assert.Single(schedulerWorkspace.ColumnDefinitions);
        Assert.Equal(2, schedulerWorkspace.RowDefinitions.Count);

        BrowserIntegrationView browser = new();
        browser.ApplyResponsiveLayout(760);
        Grid browserSummary = Assert.IsType<Grid>(browser.FindControl<Grid>("BrowserSummaryGrid"));
        Assert.Contains("compact-browser", browser.Classes);
        Assert.Single(browserSummary.ColumnDefinitions);
        Assert.Equal(3, browserSummary.RowDefinitions.Count);

        ConversionView conversion = new();
        conversion.ApplyResponsiveLayout(760);
        Grid conversionWorkspace = Assert.IsType<Grid>(conversion.FindControl<Grid>("ConversionWorkspace"));
        Assert.Contains("compact-conversion", conversion.Classes);
        Assert.Single(conversionWorkspace.ColumnDefinitions);
        Assert.Equal(2, conversionWorkspace.RowDefinitions.Count);
    }

    [AvaloniaFact]
    public void NumericSettingsUseNumericControls()
    {
        MediaView media = new();
        SchedulerView scheduler = new();

        Assert.NotNull(media.FindControl<NumericUpDown>("MediaLiveMaximumSizeInput"));
        Assert.NotNull(scheduler.GetLogicalDescendants().OfType<NumericUpDown>()
            .SingleOrDefault(control => AutomationProperties.GetAutomationId(control) == "Scheduler_Localization_ui_countdown_seconds"));
        Assert.NotNull(scheduler.GetLogicalDescendants().OfType<NumericUpDown>()
            .SingleOrDefault(control => AutomationProperties.GetAutomationId(control) == "Scheduler_Localization_ui_scan_timeout_seconds"));
    }

}
