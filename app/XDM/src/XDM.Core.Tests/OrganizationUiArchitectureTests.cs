using System.Xml.Linq;

namespace XDM.Core.Tests;

public sealed class OrganizationUiArchitectureTests
{
    [Fact]
    public void DownloadsExposeSmartCollectionsTagsArchiveAndRelink()
    {
        XElement root = Load("DownloadsView.axaml");
        XElement[] elements = root.DescendantsAndSelf().ToArray();

        Assert.Contains(elements, static element =>
            string.Equals(element.Attribute("AutomationProperties.AutomationId")?.Value, "SavedSearchSelector", StringComparison.Ordinal));
        Assert.Contains(elements, static element =>
            string.Equals(element.Attribute("AutomationProperties.AutomationId")?.Value, "NewDownloadTags", StringComparison.Ordinal));
        Assert.Contains(elements, static element =>
            string.Equals(element.Attribute("AutomationProperties.AutomationId")?.Value, "SelectedDownloadTags", StringComparison.Ordinal));
        Assert.Contains(elements, static element =>
            string.Equals(element.Attribute("AutomationProperties.AutomationId")?.Value, "RelinkDestinationPath", StringComparison.Ordinal));
        Assert.Contains(elements, static element =>
            string.Equals(element.Attribute("Command")?.Value, "{Binding ToggleSelectedDownloadArchiveCommand}", StringComparison.Ordinal));
    }

    [Fact]
    public void SettingsExposeDuplicatePolicyAndDestinationRules()
    {
        XElement root = Load("SettingsView.axaml");
        XElement[] elements = root.DescendantsAndSelf().ToArray();

        Assert.Contains(elements, static element =>
            string.Equals(element.Attribute("AutomationProperties.AutomationId")?.Value, "DuplicateUrlBehavior", StringComparison.Ordinal));
        Assert.Contains(elements, static element =>
            string.Equals(element.Attribute("AutomationProperties.AutomationId")?.Value, "ComputeContentHashes", StringComparison.Ordinal));
        Assert.Contains(elements, static element =>
            string.Equals(element.Attribute("AutomationProperties.AutomationId")?.Value, "DestinationRules", StringComparison.Ordinal));
    }

    private static XElement Load(string fileName)
        => Assert.IsType<XElement>(XDocument.Load(
            Path.Combine(AppContext.BaseDirectory, "Fixtures", "Views", fileName),
            LoadOptions.None).Root);
}
