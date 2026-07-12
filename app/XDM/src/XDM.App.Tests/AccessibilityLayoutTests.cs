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

}
