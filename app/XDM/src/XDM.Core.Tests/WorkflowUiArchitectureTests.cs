using System.Xml.Linq;

namespace XDM.Core.Tests;

public sealed class WorkflowUiArchitectureTests
{
    [Fact]
    public void DownloadsViewUsesContextualBulkActionsAndResizableDetails()
    {
        XDocument document = LoadView("DownloadsView.axaml");
        XElement root = Assert.IsType<XElement>(document.Root);
        XElement[] elements = root.DescendantsAndSelf().ToArray();

        XElement splitter = Assert.Single(
            elements,
            static element => string.Equals(
                element.Attribute("AutomationProperties.AutomationId")?.Value,
                "DownloadDetailsSplitter",
                StringComparison.Ordinal));
        Assert.Equal("GridSplitter", splitter.Name.LocalName);
        Assert.Equal("Columns", splitter.Attribute("ResizeDirection")?.Value);

        XElement bulkActionBar = Assert.Single(
            elements,
            static element => string.Equals(
                element.Attribute("AutomationProperties.AutomationId")?.Value,
                "BulkActionBar",
                StringComparison.Ordinal));
        Assert.Equal("{Binding HasBulkSelection}", bulkActionBar.Attribute("IsVisible")?.Value);

        Assert.Contains(
            elements,
            static element => element.Name.LocalName == "Expander"
                && string.Equals(
                    element.Attribute("Header")?.Value,
                    "{Binding Localization[ui_more_actions]}",
                    StringComparison.Ordinal));
        Assert.Contains(
            elements,
            static element => element.Name.LocalName == "Expander"
                && string.Equals(
                    element.Attribute("Header")?.Value,
                    "{Binding Localization[ui_file_and_source_actions]}",
                    StringComparison.Ordinal));
    }

    [Fact]
    public void SettingsViewUsesFocusedCategoriesAndPersistentSaveBar()
    {
        XDocument document = LoadView("SettingsView.axaml");
        XElement root = Assert.IsType<XElement>(document.Root);
        XElement[] elements = root.DescendantsAndSelf().ToArray();

        XElement categories = Assert.Single(
            elements,
            static element => string.Equals(
                element.Attribute("AutomationProperties.AutomationId")?.Value,
                "SettingsCategories",
                StringComparison.Ordinal));
        Assert.Equal("TabControl", categories.Name.LocalName);
        Assert.Equal(5, categories.Elements().Count(static element => element.Name.LocalName == "TabItem"));

        XElement saveBar = Assert.Single(
            elements,
            static element => string.Equals(
                element.Attribute("AutomationProperties.AutomationId")?.Value,
                "SettingsSaveBar",
                StringComparison.Ordinal));
        Assert.Equal("Border", saveBar.Name.LocalName);
        Assert.Contains(
            saveBar.Descendants(),
            static element => string.Equals(
                element.Attribute("AutomationProperties.AutomationId")?.Value,
                "SaveSettings",
                StringComparison.Ordinal));
    }

    private static XDocument LoadView(string name)
        => XDocument.Load(
            Path.Combine(AppContext.BaseDirectory, "Fixtures", "Views", name),
            LoadOptions.None);
}
