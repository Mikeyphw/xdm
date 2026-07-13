using System.Text.Json;
using System.Xml.Linq;

namespace XDM.Core.Tests;

public sealed class BackendSelectionUiArchitectureTests
{
    [Fact]
    public void DownloadsExposeBackendPreferenceFallbackAndOwnershipDetails()
    {
        XElement root = LoadView("DownloadsView.axaml");
        string[] expectedIds =
        [
            "NewDownloadBackendPreference",
            "NewDownloadAllowBackendFallback"
        ];
        string[] ids = root.DescendantsAndSelf()
            .Select(static element => element.Attribute("AutomationProperties.AutomationId")?.Value)
            .Where(static value => !string.IsNullOrWhiteSpace(value))
            .Cast<string>()
            .ToArray();

        Assert.All(expectedIds, id => Assert.Contains(id, ids));
        Assert.Contains(root.Descendants(), static element =>
            string.Equals(element.Attribute("Text")?.Value, "{Binding SelectedDownload.BackendDecisionReason}", StringComparison.Ordinal));
    }

    [Fact]
    public void Aria2SettingsExposeAutomaticRoutingPolicy()
    {
        XElement root = LoadView("SettingsView.axaml");
        string[] expectedIds =
        [
            "Aria2AutomaticRoutingEnabled",
            "Aria2AllowNativeFallback",
            "Aria2PreferForMirrors",
            "Aria2AdoptExistingTasks",
            "Aria2RoutingMinimumSize",
            "Aria2RoutingMinimumConnections"
        ];
        string[] ids = root.DescendantsAndSelf()
            .Select(static element => element.Attribute("AutomationProperties.AutomationId")?.Value)
            .Where(static value => !string.IsNullOrWhiteSpace(value))
            .Cast<string>()
            .ToArray();

        Assert.All(expectedIds, id => Assert.Contains(id, ids));
    }

    [Fact]
    public void BackendSelectionLocalizationKeysResolve()
    {
        using JsonDocument document = JsonDocument.Parse(File.ReadAllText(
            Path.Combine(AppContext.BaseDirectory, "Fixtures", "strings.en.json")));
        string[] keys =
        [
            "ui_download_backend",
            "ui_allow_native_fallback",
            "ui_backend_ownership",
            "ui_backend_decision",
            "ui_aria2_automatic_routing",
            "ui_backend_ownership_note"
        ];

        Assert.All(keys, key => Assert.True(document.RootElement.TryGetProperty(key, out _), key));
    }

    private static XElement LoadView(string fileName)
        => Assert.IsType<XElement>(XDocument.Load(
            Path.Combine(AppContext.BaseDirectory, "Fixtures", "Views", fileName),
            LoadOptions.None).Root);
}
