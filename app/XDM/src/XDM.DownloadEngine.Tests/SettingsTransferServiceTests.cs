using System.Text.Json;
using XDM.Core.Localization;
using XDM.Core.Settings;
using XDM.Persistence;

namespace XDM.DownloadEngine.Tests;

public sealed class SettingsTransferServiceTests
{
    [Fact]
    public async Task ExportsPasswordsRedactedByDefaultAndRoundTripsModernEnvelope()
    {
        string directory = CreateTemporaryDirectory();
        try
        {
            string path = Path.Combine(directory, "settings-export.json");
            ApplicationSettings settings = ApplicationSettings.CreateDefault() with
            {
                Network = NetworkSettings.Default with
                {
                    Proxy = new ProxySettings(
                        ProxyMode.Manual,
                        "proxy.example.test",
                        8080,
                        "proxy-user",
                        "proxy-secret",
                        true,
                        [])
                },
                Credentials = [new ServerCredentialDefinition("example.test", "alice", "server-secret", true)]
            };
            SettingsTransferService service = new();

            await service.ExportAsync(path, settings, includeSecrets: false);
            string exported = await File.ReadAllTextAsync(path);
            SettingsImportResult imported = await service.ImportAsync(path, ApplicationSettings.CreateDefault());

            Assert.DoesNotContain("proxy-secret", exported);
            Assert.DoesNotContain("server-secret", exported);
            Assert.Equal("modern-json", imported.SourceFormat);
            Assert.Equal(ProxyMode.Manual, imported.Settings.Network!.Proxy!.Mode);
            Assert.Null(imported.Settings.Network.Proxy.Password);
            Assert.Equal(string.Empty, Assert.Single(imported.Settings.Credentials!).Password);
        }
        finally
        {
            Directory.Delete(directory, recursive: true);
        }
    }

    [Fact]
    public async Task ImportsRecordedLegacyPropertiesWithCategoriesQueuesAndProxy()
    {
        string fixture = Path.Combine(AppContext.BaseDirectory, "Fixtures", "Legacy", "xdm8-settings.properties");
        SettingsTransferService service = new();

        SettingsImportResult result = await service.ImportAsync(fixture, ApplicationSettings.CreateDefault());

        Assert.Equal("xdm8-settings.properties", result.SourceFormat);
        Assert.Equal(7, result.Settings.MaxConcurrentDownloads);
        Assert.Equal(6, result.Settings.Network!.MaximumRetryAttempts);
        Assert.Equal(8, result.Settings.Network.DefaultConnectionCount);
        Assert.Equal(ProxyMode.Manual, result.Settings.Network.Proxy!.Mode);
        Assert.Equal("legacy-user", result.Settings.Network.Proxy.Username);
        Assert.Equal("Videos", Assert.Single(result.Settings.Categories).Name);
        Assert.Equal("Night queue", Assert.Single(result.Settings.Queues).Name);
    }

    [Fact]
    public async Task ImportsFlattenedLegacyJson()
    {
        string directory = CreateTemporaryDirectory();
        try
        {
            string path = Path.Combine(directory, "legacy.json");
            await File.WriteAllTextAsync(path, JsonSerializer.Serialize(new
            {
                defaultDownloadDirectory = "/tmp/json-downloads",
                maxRetry = 5,
                proxyMode = "none"
            }));
            SettingsTransferService service = new();

            SettingsImportResult result = await service.ImportAsync(path, ApplicationSettings.CreateDefault());

            Assert.Equal("legacy-json", result.SourceFormat);
            Assert.Equal("/tmp/json-downloads", result.Settings.DefaultDownloadDirectory);
            Assert.Equal(5, result.Settings.Network!.MaximumRetryAttempts);
            Assert.Equal(ProxyMode.None, result.Settings.Network.Proxy!.Mode);
        }
        finally
        {
            Directory.Delete(directory, recursive: true);
        }
    }


    [Fact]
    public async Task ImportsLegacyLanguageContrastAndScaleSettings()
    {
        string directory = CreateTemporaryDirectory();
        try
        {
            string path = Path.Combine(directory, "legacy.properties");
            await File.WriteAllTextAsync(
                path,
                "language=Portuguese Brazil\nhighContrast=true\nuiScalePercent=140\nannounceStatusChanges=false\n");
            SettingsTransferService service = new();

            SettingsImportResult result = await service.ImportAsync(path, ApplicationSettings.CreateDefault());

            Assert.Equal("pt-BR", result.Settings.Localization!.LanguageId);
            Assert.False(result.Settings.Localization.UseSystemLanguage);
            Assert.True(result.Settings.Accessibility!.HighContrastEnabled);
            Assert.Equal(140, result.Settings.Accessibility.UiScalePercent);
            Assert.False(result.Settings.Accessibility.AnnounceStatusChanges);
        }
        finally
        {
            Directory.Delete(directory, recursive: true);
        }
    }

    private static string CreateTemporaryDirectory()
    {
        string path = Path.Combine(Path.GetTempPath(), $"xdm-settings-transfer-{Guid.NewGuid():N}");
        Directory.CreateDirectory(path);
        return path;
    }
}
