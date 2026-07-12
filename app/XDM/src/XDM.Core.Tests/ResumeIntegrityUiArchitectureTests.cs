using System.Text.Json;
using System.Xml.Linq;

namespace XDM.Core.Tests;

public sealed class ResumeIntegrityUiArchitectureTests
{
    [Fact]
    public void DownloadsViewExposesRecoveryIntegrityAndMetalinkControls()
    {
        XElement root = Assert.IsType<XElement>(XDocument.Load(
            Path.Combine(AppContext.BaseDirectory, "Fixtures", "Views", "DownloadsView.axaml"),
            LoadOptions.None).Root);
        XElement[] elements = root.DescendantsAndSelf().ToArray();
        string[] automationIds = elements
            .Select(static element => element.Attribute("AutomationProperties.AutomationId")?.Value)
            .Where(static value => !string.IsNullOrWhiteSpace(value))
            .Cast<string>()
            .ToArray();

        Assert.Contains("RecoveryReviewBanner", automationIds);
        Assert.Contains("ImportMetalink", automationIds);
        Assert.Contains("NewDownloadChecksumAlgorithm", automationIds);
        Assert.Contains("NewDownloadExpectedChecksum", automationIds);
        Assert.Contains("NewDownloadMirrors", automationIds);
        Assert.Contains(elements, static element =>
            string.Equals(element.Attribute("Command")?.Value, "{Binding VerifySelectedDownloadCommand}", StringComparison.Ordinal));
        Assert.Contains(elements, static element =>
            string.Equals(element.Attribute("Command")?.Value, "{Binding RepairSelectedDownloadCommand}", StringComparison.Ordinal));
    }

    [Fact]
    public void ResumeIntegrityLocalizationKeysResolve()
    {
        using JsonDocument document = JsonDocument.Parse(File.ReadAllText(
            Path.Combine(AppContext.BaseDirectory, "Fixtures", "strings.en.json")));
        string[] keys =
        [
            "ui_import_metalink",
            "ui_integrity_mirrors",
            "ui_recovery_review",
            "ui_integrity_recovery",
            "ui_verify",
            "ui_repair",
            "ui_recovery_unclean_no_damage",
            "ui_recovery_items_one",
            "ui_recovery_items_many",
            "integrity_verified",
            "integrity_mismatch",
        ];

        Assert.All(keys, key => Assert.True(document.RootElement.TryGetProperty(key, out _), key));
    }
}
