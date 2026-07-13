using System.Text.Json;
using System.Xml.Linq;

namespace XDM.Core.Tests;

public sealed class SmartQueueUiArchitectureTests
{
    [Fact]
    public void QueuesViewExposesSmartPolicyProfilesAndDependencies()
    {
        XElement root = LoadView("QueuesView.axaml");
        string[] automationIds = root.DescendantsAndSelf()
            .Select(static element => element.Attribute("AutomationProperties.AutomationId")?.Value)
            .Where(static value => !string.IsNullOrWhiteSpace(value))
            .Cast<string>()
            .ToArray();

        string[] expected =
        [
            "Queues_SmartTransfersEnabled",
            "Queues_ActiveBandwidthProfile",
            "Queues_ProfilePerHost",
            "Queues_DependencyCandidate",
            "Queues_RequireSuccessfulDependencies"
        ];
        Assert.All(expected, id => Assert.Contains(id, automationIds));
    }

    [Fact]
    public void SchedulerViewOffersBandwidthProfileOverride()
    {
        XElement root = LoadView("SchedulerView.axaml");

        XElement profile = Assert.Single(root.DescendantsAndSelf(), static element =>
            string.Equals(
                element.Attribute("AutomationProperties.AutomationId")?.Value,
                "Scheduler_BandwidthProfile",
                StringComparison.Ordinal));
        Assert.Equal("ComboBox", profile.Name.LocalName);
        Assert.Equal("{Binding SelectedSchedule.Profiles}", profile.Attribute("ItemsSource")?.Value);
    }

    [Fact]
    public void SmartQueueLocalizationKeysResolve()
    {
        using JsonDocument document = JsonDocument.Parse(File.ReadAllText(
            Path.Combine(AppContext.BaseDirectory, "Fixtures", "strings.en.json")));
        string[] keys =
        [
            "ui_smart_transfer_policy",
            "ui_metered_behavior",
            "ui_profile_per_host",
            "ui_queue_dependencies",
            "ui_schedule_bandwidth_profile"
        ];

        Assert.All(keys, key => Assert.True(document.RootElement.TryGetProperty(key, out _), key));
    }

    private static XElement LoadView(string name)
        => Assert.IsType<XElement>(XDocument.Load(
            Path.Combine(AppContext.BaseDirectory, "Fixtures", "Views", name),
            LoadOptions.None).Root);
}
