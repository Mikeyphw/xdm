using XDM.Core.Downloads;
using XDM.Core.Settings;
using XDM.Persistence;

namespace XDM.DownloadEngine.Tests;

public sealed class MigrationQualificationTests
{
    private static string Fixture(string name)
        => Path.Combine(AppContext.BaseDirectory, "Fixtures", "Legacy", "FinalGate", name);

    [Theory]
    [InlineData("xdm8-settings.xml", "xdm8-settings.xml")]
    [InlineData("xdm8-settings.json", "legacy-json")]
    public async Task ImportsRepresentativeLegacySettingsFormats(string name, string expectedFormat)
    {
        SettingsTransferService service = new();

        SettingsImportResult result = await service.ImportAsync(
            Fixture(name),
            ApplicationSettings.CreateDefault());

        Assert.Equal(expectedFormat, result.SourceFormat);
        Assert.Equal(ApplicationSettings.CurrentSchemaVersion, result.Settings.SchemaVersion);
        Assert.InRange(result.Settings.MaxConcurrentDownloads, 1, 32);
        Assert.NotEmpty(result.Settings.Categories);
        Assert.NotEmpty(result.Settings.Queues);
        Assert.NotNull(result.Settings.Network);
        Assert.NotNull(result.Settings.Localization);
        Assert.NotNull(result.Settings.Accessibility);
        SmartTransferSettings smartTransfers = Assert.IsType<SmartTransferSettings>(result.Settings.SmartTransfers);
        Assert.NotEmpty(smartTransfers.Profiles);
        Assert.Equal(XDM.Core.Product.UpdateChannel.Stable, result.Settings.Updates?.Channel);
    }

    [Fact]
    public async Task LoadsLegacyHistoryWithoutLosingPartialState()
    {
        JsonDownloadHistoryStore store = new(Fixture("xdm8-history.json"));

        IReadOnlyList<XDM.Core.Persistence.PersistedDownload> history = await store.LoadAsync();

        Assert.Equal(2, history.Count);
        XDM.Core.Persistence.PersistedDownload paused = Assert.Single(
            history,
            static item => item.Id == "legacy-paused");
        Assert.Equal(DownloadState.Paused, paused.State);
        Assert.Null(paused.TotalBytes);
        Assert.Equal("night", paused.QueueId);
        Assert.Equal("video", paused.CategoryId);
        Assert.Equal("\"legacy-v1\"", paused.EntityTag);
    }

    [Fact]
    public async Task LoadsPersistedSchedulerWindowForMissedRunEvaluation()
    {
        JsonSchedulerStateStore store = new(Fixture("xdm8-scheduler-state.json"));

        XDM.Core.Scheduling.SchedulerRuntimeState state = await store.LoadAsync();

        Assert.Equal(
            DateTimeOffset.Parse("2026-07-11T23:00:00Z", System.Globalization.CultureInfo.InvariantCulture),
            state.LastEvaluationUtc);
        Assert.Equal(
            DateTimeOffset.Parse("2026-07-11T22:00:00Z", System.Globalization.CultureInfo.InvariantCulture),
            state.LastStartedWindows["night-schedule"]);
    }

    [Fact]
    public async Task MigrationExportRemainsCredentialFreeByDefault()
    {
        string path = Path.Combine(Path.GetTempPath(), $"xdm-final-gate-{Guid.NewGuid():N}.json");
        try
        {
            ApplicationSettings settings = ApplicationSettings.CreateDefault() with
            {
                Credentials = [new ServerCredentialDefinition("example.test", "legacy-user", "secret", false)]
            };
            SettingsTransferService service = new();

            await service.ExportAsync(path, settings, includeSecrets: false);
            string exported = await File.ReadAllTextAsync(path);

            Assert.DoesNotContain("secret", exported, StringComparison.Ordinal);
            Assert.Contains("legacy-user", exported, StringComparison.Ordinal);
        }
        finally
        {
            File.Delete(path);
        }
    }
}
