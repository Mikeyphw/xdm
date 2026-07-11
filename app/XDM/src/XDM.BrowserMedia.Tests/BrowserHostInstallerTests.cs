using System.Text.Json;
using XDM.BrowserIntegration;

namespace XDM.BrowserMedia.Tests;

public sealed class BrowserHostInstallerTests
{
    [Fact]
    public async Task RepairsFirefoxAndChromiumUserManifests()
    {
        string root = Path.Combine(Path.GetTempPath(), $"xdm-host-{Guid.NewGuid():N}");
        string host = Path.Combine(root, "XDM.NativeHost");
        Directory.CreateDirectory(root);
        await File.WriteAllTextAsync(host, "host");
        try
        {
            BrowserHostInstaller installer = new(host, root);
            BrowserHostInstallationStatus status = await installer.RepairAsync("abcdefghijklmnopabcdefghijklmnop");

            Assert.True(status.NativeHostExists);
            Assert.True(status.FirefoxManifestInstalled);
            Assert.Equal(5, status.ChromiumManifestCount);
            string manifestPath = Path.Combine(root, ".mozilla", "native-messaging-hosts", $"{BrowserHostInstaller.HostName}.json");
            using JsonDocument manifest = JsonDocument.Parse(await File.ReadAllTextAsync(manifestPath));
            Assert.Equal(host, manifest.RootElement.GetProperty("path").GetString());
        }
        finally
        {
            Directory.Delete(root, recursive: true);
        }
    }
}
