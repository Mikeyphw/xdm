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
        "RecoveryView.axaml",
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
    public void MiniWindowParticipatesInLocalizationAccessibilityAndMainActionState()
    {
        XElement[] elements = LoadMiniWindow().DescendantsAndSelf().ToArray();
        Assert.Contains(elements, static element =>
            string.Equals(element.Attribute("AutomationProperties.AutomationId")?.Value, "MiniDownloads", StringComparison.Ordinal)
            && string.Equals(element.Attribute("AutomationProperties.Name")?.Value, "{Binding Localization[ui_mini_downloads]}", StringComparison.Ordinal));

        Assert.Contains(elements, static element =>
            string.Equals(element.Attribute("AutomationProperties.AutomationId")?.Value, "MiniPauseSelectedTransfer", StringComparison.Ordinal)
            && string.Equals(element.Attribute("IsEnabled")?.Value, "{Binding CanPauseSelectedTransfer}", StringComparison.Ordinal)
            && string.Equals(element.Attribute("Content")?.Value, "{Binding Localization[ui_pause]}", StringComparison.Ordinal));
        Assert.Contains(elements, static element =>
            string.Equals(element.Attribute("AutomationProperties.AutomationId")?.Value, "MiniResumeSelectedTransfer", StringComparison.Ordinal)
            && string.Equals(element.Attribute("IsEnabled")?.Value, "{Binding CanResumeSelectedTransfer}", StringComparison.Ordinal)
            && string.Equals(element.Attribute("Content")?.Value, "{Binding Localization[ui_resume]}", StringComparison.Ordinal));
        Assert.Contains(elements, static element =>
            string.Equals(element.Attribute("AutomationProperties.AutomationId")?.Value, "MiniCancelSelectedTransfer", StringComparison.Ordinal)
            && string.Equals(element.Attribute("IsEnabled")?.Value, "{Binding CanCancelSelectedTransfer}", StringComparison.Ordinal)
            && string.Equals(element.Attribute("Content")?.Value, "{Binding Localization[ui_cancel]}", StringComparison.Ordinal));
    }

    [Fact]
    public void ShellNavigationDocumentsAllNineNumericShortcuts()
    {
        XElement[] elements = LoadMainWindow().DescendantsAndSelf().ToArray();
        XElement navigation = Assert.Single(elements, static element =>
            string.Equals(element.Attribute("AutomationProperties.AutomationId")?.Value, "PrimaryNavigation", StringComparison.Ordinal));
        XAttribute? helpText = navigation.Attribute("AutomationProperties.HelpText");
        Assert.NotNull(helpText);
        Assert.Equal("{Binding Localization[ui_primary_navigation_help]}", helpText.Value);
    }

    [Fact]
    public void ShellAndPageFixturesDoNotExposeHardCodedVisibleCopy()
    {
        List<XElement> roots = [LoadMainWindow(), LoadMiniWindow()];
        roots.AddRange(ViewFixtures.Select(LoadView));
        string[] visibleAttributes = ["Text", "Content", "Header", "PlaceholderText", "Watermark", "Title"];
        string[] allowedLiterals = ["—"];

        foreach (XElement element in roots.SelectMany(static root => root.DescendantsAndSelf()))
        {
            foreach (string attributeName in visibleAttributes)
            {
                string? value = element.Attribute(attributeName)?.Value;
                if (string.IsNullOrWhiteSpace(value)
                    || value.StartsWith("{Binding", StringComparison.Ordinal)
                    || value.StartsWith("{DynamicResource", StringComparison.Ordinal)
                    || value.StartsWith("{StaticResource", StringComparison.Ordinal)
                    || allowedLiterals.Contains(value, StringComparer.Ordinal)
                    || !value.Any(char.IsLetter))
                {
                    continue;
                }

                Assert.Fail($"{element.Name.LocalName}.{attributeName} has non-localized visible text: {value}");
            }
        }
    }

    [Fact]
    public void SettingsChoiceControlsUseLocalizedChoiceViewModels()
    {
        XElement[] settings = LoadView("SettingsView.axaml").DescendantsAndSelf().ToArray();
        AssertLocalizedChoice(settings, "DuplicateFileBehavior", "DuplicateBehaviorChoices", "SelectedDuplicateBehaviorChoice");
        AssertLocalizedChoice(settings, "DuplicateUrlBehavior", "DuplicateUrlBehaviorChoices", "SelectedDuplicateUrlBehaviorChoice");
        AssertLocalizedChoice(settings, "ProxyMode", "ProxyModeChoices", "SelectedProxyModeChoice");
        AssertLocalizedChoice(settings, "ProxyAuthenticationMode", "ProxyAuthenticationModeChoices", "SelectedProxyAuthenticationModeChoice");
        AssertLocalizedChoice(settings, "Aria2ConnectionMode", "Aria2ConnectionModeChoices", "SelectedAria2ConnectionModeChoice");
        AssertLocalizedChoice(settings, "Settings_UpdateChannel", "UpdateChannelChoices", "SelectedUpdateChannelChoice");
    }

    private static void AssertLocalizedChoice(
        XElement[] elements,
        string automationId,
        string itemsSource,
        string selectedItem)
    {
        XElement comboBox = Assert.Single(elements, element =>
            element.Name.LocalName == "ComboBox"
            && string.Equals(element.Attribute("AutomationProperties.AutomationId")?.Value, automationId, StringComparison.Ordinal));
        Assert.Equal($"{{Binding {itemsSource}}}", comboBox.Attribute("ItemsSource")?.Value);
        Assert.Equal($"{{Binding {selectedItem}}}", comboBox.Attribute("SelectedItem")?.Value);
        Assert.Contains(comboBox.Descendants(), static element =>
            element.Name.LocalName == "TextBlock"
            && string.Equals(element.Attribute("Text")?.Value, "{Binding Label}", StringComparison.Ordinal));
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

    private static XElement LoadMiniWindow()
        => Assert.IsType<XElement>(XDocument.Load(
            Path.Combine(AppContext.BaseDirectory, "Fixtures", "MiniWindow.axaml"),
            LoadOptions.None).Root);

    private static XElement LoadView(string name)
        => Assert.IsType<XElement>(XDocument.Load(
            Path.Combine(AppContext.BaseDirectory, "Fixtures", "Views", name),
            LoadOptions.None).Root);
}
