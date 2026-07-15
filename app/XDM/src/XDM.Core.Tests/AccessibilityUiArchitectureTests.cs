using System.Xml.Linq;

namespace XDM.Core.Tests;

public sealed class AccessibilityUiArchitectureTests
{
    private static readonly string[] ViewFixtures =
    [
        "BrowserIntegrationView.axaml",
        "ConversionView.axaml",
        "DiagnosticsView.axaml",
        "DownloadsView.axaml",
        "MediaView.axaml",
        "QueuesView.axaml",
        "SchedulerView.axaml",
        "SettingsView.axaml",
    ];

    [Fact]
    public void ShellExposesNavigationMainAndContentInfoLandmarks()
    {
        XElement[] elements = LoadMainWindow().DescendantsAndSelf().ToArray();
        string[] landmarks = elements
            .Select(static element => element.Attribute("AutomationProperties.LandmarkType")?.Value)
            .Where(static value => !string.IsNullOrWhiteSpace(value))
            .Cast<string>()
            .ToArray();

        Assert.Contains("Navigation", landmarks);
        Assert.Contains("Main", landmarks);
        Assert.Contains("ContentInfo", landmarks);
        Assert.Contains(elements, static element =>
            string.Equals(element.Attribute("AutomationProperties.HeadingLevel")?.Value, "1", StringComparison.Ordinal));
    }

    [Theory]
    [MemberData(nameof(ViewFixtureNames))]
    public void PageViewsExposeKeyboardNavigationLandmarksAndLabeledFormControls(string fixtureName)
    {
        XElement root = LoadView(fixtureName);
        Assert.Equal("Continue", root.Attribute("KeyboardNavigation.TabNavigation")?.Value);
        Assert.NotEqual("Main", root.Attribute("AutomationProperties.LandmarkType")?.Value);

        XElement[] formControls = root.DescendantsAndSelf()
            .Where(static element => element.Name.LocalName is "TextBox" or "ComboBox" or "NumericUpDown" or "ListBox" or "TabControl")
            .ToArray();
        Assert.All(formControls, static control =>
        {
            bool hasLabel = control.Attribute("AutomationProperties.Name") is not null
                || control.Attribute("AutomationProperties.LabeledBy") is not null;
            Assert.True(hasLabel, $"{control.Name.LocalName} is missing an accessible label.");
            Assert.False(
                string.IsNullOrWhiteSpace(control.Attribute("AutomationProperties.AutomationId")?.Value),
                $"{control.Name.LocalName} is missing a stable automation id.");
        });
    }

    [Fact]
    public void AutomationIdsAreUniqueAcrossShellAndPages()
    {
        List<string> ids = LoadMainWindow().DescendantsAndSelf()
            .Select(static element => element.Attribute("AutomationProperties.AutomationId")?.Value)
            .Where(static value => !string.IsNullOrWhiteSpace(value))
            .Cast<string>()
            .ToList();
        foreach (string fixtureName in ViewFixtures)
        {
            ids.AddRange(LoadView(fixtureName).DescendantsAndSelf()
                .Select(static element => element.Attribute("AutomationProperties.AutomationId")?.Value)
                .Where(static value => !string.IsNullOrWhiteSpace(value))
                .Cast<string>());
        }

        string[] duplicates = ids
            .GroupBy(static id => id, StringComparer.Ordinal)
            .Where(static group => group.Count() > 1)
            .Select(static group => group.Key)
            .ToArray();
        Assert.Empty(duplicates);
    }

    [Fact]
    public void SettingsProvidesAccessibleValidationSummaryAndSaveShortcut()
    {
        XElement[] elements = LoadView("SettingsView.axaml").DescendantsAndSelf().ToArray();
        XElement validation = Assert.Single(elements, static element =>
            string.Equals(element.Attribute("AutomationProperties.AutomationId")?.Value, "SettingsValidationSummary", StringComparison.Ordinal));
        Assert.Equal("Assertive", validation.Attribute("AutomationProperties.LiveSetting")?.Value);

        XElement save = Assert.Single(elements, static element =>
            string.Equals(element.Attribute("AutomationProperties.AutomationId")?.Value, "SaveSettings", StringComparison.Ordinal));
        Assert.Equal("Ctrl+S", save.Attribute("AutomationProperties.AcceleratorKey")?.Value);
    }

    public static TheoryData<string> ViewFixtureNames()
    {
        TheoryData<string> data = new();
        foreach (string fixtureName in ViewFixtures)
        {
            data.Add(fixtureName);
        }

        return data;
    }

    private static XElement LoadMainWindow()
        => Assert.IsType<XElement>(XDocument.Load(
            Path.Combine(AppContext.BaseDirectory, "Fixtures", "MainWindow.axaml"),
            LoadOptions.None).Root);

    private static XElement LoadView(string name)
        => Assert.IsType<XElement>(XDocument.Load(
            Path.Combine(AppContext.BaseDirectory, "Fixtures", "Views", name),
            LoadOptions.None).Root);
}
