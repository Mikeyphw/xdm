using System.Text.Json;
using XDM.BrowserIntegration;

namespace XDM.BrowserMedia.Tests;

public sealed class BrowserHostInstallerTests
{
    private static readonly string[] UntrustedFirefoxExtensions = ["untrusted@example.test"];

    [Fact]
    public async Task RepairsAndUninstallsFirefoxAndChromiumFamilyManifests()
    {
        string root = Path.Combine(Path.GetTempPath(), $"xdm-host-{Guid.NewGuid():N}");
        string host = Path.Combine(root, "XDM.NativeHost");
        Directory.CreateDirectory(root);
        await File.WriteAllTextAsync(host, "host");
        try
        {
            BrowserHostInstaller installer = new(host, root, BrowserHostPlatform.Linux);
            BrowserHostInstallationStatus status = await installer.RepairAsync(
                "abcdefghijklmnopabcdefghijklmnop");

            Assert.True(status.NativeHostExists);
            Assert.True(status.FirefoxManifestInstalled);
            Assert.Equal(6, status.ChromiumManifestCount);
            Assert.Equal(7, status.CompatibleManifestCount);
            Assert.True(status.IsCompatible);
            string manifestPath = Path.Combine(
                root,
                ".mozilla",
                "native-messaging-hosts",
                $"{BrowserHostInstaller.HostName}.json");
            using JsonDocument manifest = JsonDocument.Parse(await File.ReadAllTextAsync(manifestPath));
            Assert.Equal(host, manifest.RootElement.GetProperty("path").GetString());
            Assert.Contains(
                BrowserHostInstaller.FirefoxExtensionId,
                manifest.RootElement.GetProperty("allowed_extensions")
                    .EnumerateArray()
                    .Select(static item => item.GetString())
                    .OfType<string>());

            BrowserHostInstallationStatus removed = await installer.UninstallAsync();
            Assert.False(removed.FirefoxManifestInstalled);
            Assert.Equal(0, removed.ChromiumManifestCount);
            Assert.False(removed.IsCompatible);
        }
        finally
        {
            Directory.Delete(root, recursive: true);
        }
    }

    [Fact]
    public async Task InstallsFirefoxWithoutChromiumIdAndRejectsInvalidId()
    {
        string root = Path.Combine(Path.GetTempPath(), $"xdm-host-{Guid.NewGuid():N}");
        string host = Path.Combine(root, "XDM.NativeHost");
        Directory.CreateDirectory(root);
        await File.WriteAllTextAsync(host, "host");
        try
        {
            BrowserHostInstaller installer = new(host, root, BrowserHostPlatform.Linux);

            BrowserHostInstallationStatus firefoxOnly = await installer.RepairAsync(null);

            Assert.True(firefoxOnly.FirefoxManifestInstalled);
            Assert.Equal(0, firefoxOnly.ChromiumManifestCount);
            await Assert.ThrowsAsync<InvalidDataException>(() => installer.RepairAsync("not-an-extension-id"));
        }
        finally
        {
            Directory.Delete(root, recursive: true);
        }
    }

    [Fact]
    public async Task RejectsManifestWithUntrustedExtensionAllowList()
    {
        string root = Path.Combine(Path.GetTempPath(), $"xdm-host-{Guid.NewGuid():N}");
        string host = Path.Combine(root, "XDM.NativeHost");
        Directory.CreateDirectory(root);
        await File.WriteAllTextAsync(host, "host");
        try
        {
            BrowserHostInstaller installer = new(host, root, BrowserHostPlatform.Linux);
            await installer.RepairAsync("abcdefghijklmnopabcdefghijklmnop");
            string manifestPath = Path.Combine(
                root,
                ".mozilla",
                "native-messaging-hosts",
                $"{BrowserHostInstaller.HostName}.json");
            await File.WriteAllTextAsync(
                manifestPath,
                JsonSerializer.Serialize(new
                {
                    name = BrowserHostInstaller.HostName,
                    description = "rogue allow-list",
                    path = host,
                    type = "stdio",
                    allowed_extensions = UntrustedFirefoxExtensions
                }));

            BrowserHostInstallationStatus status = installer.GetStatus();

            Assert.False(status.IsCompatible);
            Assert.Contains(
                status.Manifests!,
                static manifest => manifest.Browser == "Firefox" && !manifest.IsCompatible);
        }
        finally
        {
            Directory.Delete(root, recursive: true);
        }
    }

}
