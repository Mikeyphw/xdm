using XDM.Core.Settings;
using XDM.Persistence;

namespace XDM.DownloadEngine.Tests;

public sealed class JsonSettingsStoreTests
{
    [Fact]
    public async Task RoundTripsNormalizedSettingsAndCreatesBackup()
    {
        string directory = Path.Combine(Path.GetTempPath(), $"xdm-settings-{Guid.NewGuid():N}");
        Directory.CreateDirectory(directory);

        try
        {
            string path = Path.Combine(directory, "settings.json");
            JsonSettingsStore store = new(path);
            ApplicationSettings settings = ApplicationSettings.CreateDefault() with
            {
                MaxConcurrentDownloads = 99,
                ClipboardMonitoringEnabled = true
            };

            await store.SaveAsync(settings);
            await store.SaveAsync(settings with { AutoAddClipboardLinks = true });
            ApplicationSettings? loaded = await store.LoadAsync();

            ApplicationSettings actual = Assert.IsType<ApplicationSettings>(loaded);
            Assert.Equal(32, actual.MaxConcurrentDownloads);
            Assert.True(actual.ClipboardMonitoringEnabled);
            Assert.True(actual.AutoAddClipboardLinks);
            Assert.True(File.Exists($"{path}.bak"));
            Assert.False(File.Exists($"{path}.tmp"));
        }
        finally
        {
            Directory.Delete(directory, recursive: true);
        }
    }
}
