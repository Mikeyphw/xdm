using System.Xml.Linq;

namespace XDM.Core.Tests;

public sealed class ShellArchitectureTests
{
    private static readonly string[] ExpectedViews =
    [
        "DownloadsView",
        "QueuesView",
        "SchedulerView",
        "BrowserIntegrationView",
        "MediaView",
        "ConversionView",
        "SettingsView",
        "DiagnosticsView",
        "RecoveryView",
    ];

    [Fact]
    public void MainWindowUsesResponsiveSplitViewAndDedicatedPages()
    {
        XDocument document = XDocument.Load(
            Path.Combine(AppContext.BaseDirectory, "Fixtures", "MainWindow.axaml"),
            LoadOptions.None);
        XElement root = Assert.IsType<XElement>(document.Root);
        XElement[] elements = root.DescendantsAndSelf().ToArray();

        XElement splitView = Assert.Single(elements, static element => element.Name.LocalName == "SplitView");
        Assert.Equal("NavigationSplitView", splitView.Attribute(XName.Get("Name", "http://schemas.microsoft.com/winfx/2006/xaml"))?.Value);
        Assert.Equal("220", splitView.Attribute("OpenPaneLength")?.Value);
        Assert.Equal("60", splitView.Attribute("CompactPaneLength")?.Value);

        string[] pageNames = elements
            .Where(static element => element.Name.NamespaceName == "using:XDM.App.Views")
            .Select(static element => element.Name.LocalName)
            .Order(StringComparer.Ordinal)
            .ToArray();
        Assert.Equal(ExpectedViews.Order(StringComparer.Ordinal).ToArray(), pageNames);

        Assert.Contains(elements, static element =>
            element.Name.LocalName == "Button"
            && string.Equals(
                element.Attribute("AutomationProperties.AutomationId")?.Value,
                "ContentNavigationToggle",
                StringComparison.Ordinal));
    }

    [Theory]
    [MemberData(nameof(PageFixtureNames))]
    public void PageFixtureHasAUserControlRootAndNoRootLevelPageVisibility(string fixtureName)
    {
        XDocument document = XDocument.Load(
            Path.Combine(AppContext.BaseDirectory, "Fixtures", "Views", fixtureName),
            LoadOptions.None);
        XElement root = Assert.IsType<XElement>(document.Root);

        Assert.Equal("UserControl", root.Name.LocalName);
        Assert.False(
            root.Attribute("IsVisible")?.Value.StartsWith("{Binding Is", StringComparison.Ordinal) == true,
            "Page visibility belongs to the shell; descendant controls may use state-specific visibility bindings.");
    }

    public static TheoryData<string> PageFixtureNames()
    {
        TheoryData<string> data = new();
        foreach (string view in ExpectedViews)
        {
            data.Add($"{view}.axaml");
        }

        return data;
    }
}
