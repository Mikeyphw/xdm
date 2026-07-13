using System.Text.Json;
using System.Xml.Linq;

namespace XDM.Core.Tests;

public sealed class MediaWorkflowUiArchitectureTests
{
    [Fact]
    public void MediaViewProvidesInboxVariantsLiveLimitsAndPostProcessing()
    {
        XElement root = Assert.IsType<XElement>(XDocument.Load(
            Path.Combine(AppContext.BaseDirectory, "Fixtures", "Views", "MediaView.axaml"),
            LoadOptions.None).Root);
        string[] ids = root.DescendantsAndSelf()
            .Select(static element => element.Attribute("AutomationProperties.AutomationId")?.Value)
            .Where(static value => !string.IsNullOrWhiteSpace(value))
            .Cast<string>()
            .ToArray();

        Assert.Contains("MediaDetectionInbox", ids);
        Assert.Contains("MediaQualityPreference", ids);
        Assert.Contains("MediaAudioLanguage", ids);
        Assert.Contains("MediaSubtitleLanguage", ids);
        Assert.Contains("MediaLiveDuration", ids);
        Assert.Contains("MediaLiveMaximumSize", ids);
        Assert.Contains("MediaPostProcessingPreset", ids);
        Assert.Contains(root.Descendants(), static element =>
            string.Equals(element.Attribute("ItemsSource")?.Value, "{Binding MediaAllFormats}", StringComparison.Ordinal));
    }

    [Fact]
    public void MediaWorkflowLocalizationKeysResolve()
    {
        using JsonDocument document = JsonDocument.Parse(File.ReadAllText(
            Path.Combine(AppContext.BaseDirectory, "Fixtures", "strings.en.json")));
        string[] keys =
        [
            "ui_media_detection_inbox",
            "ui_media_download_plan",
            "ui_media_quality",
            "ui_media_variant_inventory",
            "ui_media_live_recording",
            "ui_media_live_maximum_size_mb",
            "ui_media_output_and_processing"
        ];

        Assert.All(keys, key => Assert.True(document.RootElement.TryGetProperty(key, out _), key));
    }
}
