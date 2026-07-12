using System.Text.Json;
using System.Xml.Linq;

namespace XDM.Core.Tests;

public sealed class RuntimePolishUiArchitectureTests
{
    [Fact]
    public void ShellProvidesDismissibleLiveStatusAndContentTransition()
    {
        XElement root = LoadMainWindow();
        XElement status = Assert.Single(root.DescendantsAndSelf(), static element =>
            element.Attributes().Any(static attribute =>
                attribute.Name.LocalName == "Name"
                && string.Equals(attribute.Value, "OperationStatusBanner", StringComparison.Ordinal)));
        Assert.Equal("{Binding IsOperationMessageVisible}", status.Attribute("IsVisible")?.Value);

        Assert.Contains(root.Descendants(), static element =>
            element.Name.LocalName == "DoubleTransition"
            && string.Equals(element.Attribute("Property")?.Value, "Opacity", StringComparison.Ordinal));
        Assert.Contains(root.Descendants(), static element =>
            string.Equals(element.Attribute("AutomationProperties.AutomationId")?.Value, "DismissOperationStatus", StringComparison.Ordinal));
    }

    [Fact]
    public void DownloadsViewProvidesLoadingEmptyFilterAndDetailStates()
    {
        XElement root = LoadDownloadsView();
        string[] expectedIds =
        [
            "DownloadsLoadingState",
            "DownloadsEmptyState",
            "DownloadsFilterEmptyState",
            "DownloadDetailsEmptyState",
        ];

        string[] ids = root.DescendantsAndSelf()
            .Select(static element => element.Attribute("AutomationProperties.AutomationId")?.Value)
            .Where(static value => !string.IsNullOrWhiteSpace(value))
            .Cast<string>()
            .ToArray();
        Assert.All(expectedIds, id => Assert.Contains(id, ids));
        Assert.Contains(root.Descendants(), static element =>
            element.Name.LocalName == "Border"
            && string.Equals(element.Attribute("Classes")?.Value, "error-state", StringComparison.Ordinal));
    }

    [Fact]
    public void RuntimePolishLocalizationKeysResolve()
    {
        using JsonDocument document = JsonDocument.Parse(File.ReadAllText(
            Path.Combine(AppContext.BaseDirectory, "Fixtures", "strings.en.json")));
        string[] keys =
        [
            "ui_dismiss_status",
            "ui_loading_downloads",
            "ui_no_downloads_yet",
            "ui_no_matching_downloads",
            "ui_clear_filters",
            "ui_select_download_for_details",
            "ui_download_error",
        ];

        Assert.All(keys, key => Assert.True(document.RootElement.TryGetProperty(key, out _), key));
    }

    private static XElement LoadMainWindow()
        => Assert.IsType<XElement>(XDocument.Load(
            Path.Combine(AppContext.BaseDirectory, "Fixtures", "MainWindow.axaml"),
            LoadOptions.None).Root);

    private static XElement LoadDownloadsView()
        => Assert.IsType<XElement>(XDocument.Load(
            Path.Combine(AppContext.BaseDirectory, "Fixtures", "Views", "DownloadsView.axaml"),
            LoadOptions.None).Root);
}
