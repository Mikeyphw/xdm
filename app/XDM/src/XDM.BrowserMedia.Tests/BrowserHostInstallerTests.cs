using System.Text.Json;
using XDM.BrowserIntegration;

namespace XDM.BrowserMedia.Tests;

public sealed class BrowserHostInstallerTests
{
    [Fact]
    public async Task RepairsCanonicalFirefoxProtocolAndChromiumFamilyManifests()
    {
        string root = Path.Combine(Path.GetTempPath(), $"xdm-host-{Guid.NewGuid():N}");
        string host = Path.Combine(root, "XDM.NativeHost");
        string app = Path.Combine(root, "XDM");
        Directory.CreateDirectory(root);
        await File.WriteAllTextAsync(host, "host");
        await File.WriteAllTextAsync(app, "app");
        try
        {
            string legacyFirefoxManifest = Path.Combine(root, ".mozilla", "native-messaging-hosts", $"{BrowserHostInstaller.HostName}.json");
            Directory.CreateDirectory(Path.GetDirectoryName(legacyFirefoxManifest)!);
            await File.WriteAllTextAsync(legacyFirefoxManifest, "{}");

            BrowserHostInstaller installer = new(host, root, BrowserHostPlatform.Linux, applicationPath: app);
            BrowserHostInstallationStatus status = await installer.RepairAsync(
                "abcdefghijklmnopabcdefghijklmnop");

            Assert.True(status.NativeHostExists);
            Assert.True(status.FirefoxProtocolRegistered);
            Assert.Equal(6, status.ChromiumManifestCount);
            Assert.Equal(6, status.CompatibleManifestCount);
            Assert.True(status.IsCompatible);
            Assert.False(File.Exists(legacyFirefoxManifest));

            string desktopPath = Path.Combine(root, ".local", "share", "applications", BrowserHostInstaller.FirefoxDesktopFileName);
            string desktop = await File.ReadAllTextAsync(desktopPath);
            Assert.Contains($"X-XDM-FirefoxExtensionId={FirefoxExtensionHandoffParser.CanonicalExtensionId}", desktop, StringComparison.Ordinal);
            Assert.Contains("x-scheme-handler/xdmdownload", desktop, StringComparison.Ordinal);
            Assert.Contains("x-scheme-handler/xdmdownload-debug", desktop, StringComparison.Ordinal);
            Assert.Contains(app, desktop, StringComparison.Ordinal);

            string mimeApps = await File.ReadAllTextAsync(Path.Combine(root, ".config", "mimeapps.list"));
            Assert.Contains("x-scheme-handler/xdmdownload=xdm-modern.desktop", mimeApps, StringComparison.Ordinal);
            Assert.Contains("x-scheme-handler/xdmdownload-debug=xdm-modern.desktop", mimeApps, StringComparison.Ordinal);

            string manifestPath = Path.Combine(
                root,
                ".config",
                "chromium",
                "NativeMessagingHosts",
                $"{BrowserHostInstaller.HostName}.json");
            using JsonDocument manifest = JsonDocument.Parse(await File.ReadAllTextAsync(manifestPath));
            Assert.Equal(host, manifest.RootElement.GetProperty("path").GetString());
            Assert.Equal(
                BrowserNativeProtocol.ProtocolVersion,
                manifest.RootElement.GetProperty("xdm_protocol_version").GetString());

            BrowserHostInstallationStatus removed = await installer.UninstallAsync();
            Assert.False(removed.FirefoxProtocolRegistered);
            Assert.Equal(0, removed.ChromiumManifestCount);
            Assert.False(removed.IsCompatible);
        }
        finally
        {
            Directory.Delete(root, recursive: true);
        }
    }

    [Fact]
    public async Task CanonicalFirefoxProtocolDoesNotRequireChromiumIdOrNativeHost()
    {
        string root = Path.Combine(Path.GetTempPath(), $"xdm-host-{Guid.NewGuid():N}");
        string host = Path.Combine(root, "missing-XDM.NativeHost");
        string app = Path.Combine(root, "XDM");
        Directory.CreateDirectory(root);
        await File.WriteAllTextAsync(app, "app");
        try
        {
            BrowserHostInstaller installer = new(host, root, BrowserHostPlatform.Linux, applicationPath: app);

            BrowserHostInstallationStatus firefoxOnly = await installer.RepairAsync(null);

            Assert.False(firefoxOnly.NativeHostExists);
            Assert.True(firefoxOnly.FirefoxProtocolRegistered);
            Assert.Equal(0, firefoxOnly.ChromiumManifestCount);
            Assert.True(firefoxOnly.IsCompatible);
            await Assert.ThrowsAsync<InvalidDataException>(() => installer.RepairAsync("not-an-extension-id"));
        }
        finally
        {
            Directory.Delete(root, recursive: true);
        }
    }

    [Fact]
    public async Task RejectsTamperedFirefoxProtocolRegistration()
    {
        string root = Path.Combine(Path.GetTempPath(), $"xdm-host-{Guid.NewGuid():N}");
        string host = Path.Combine(root, "XDM.NativeHost");
        string app = Path.Combine(root, "XDM");
        Directory.CreateDirectory(root);
        await File.WriteAllTextAsync(host, "host");
        await File.WriteAllTextAsync(app, "app");
        try
        {
            BrowserHostInstaller installer = new(host, root, BrowserHostPlatform.Linux, applicationPath: app);
            await installer.RepairAsync(null);
            string desktopPath = Path.Combine(root, ".local", "share", "applications", BrowserHostInstaller.FirefoxDesktopFileName);
            string source = await File.ReadAllTextAsync(desktopPath);
            await File.WriteAllTextAsync(desktopPath, source.Replace(app, "/tmp/rogue-xdm", StringComparison.Ordinal));

            BrowserHostInstallationStatus status = installer.GetStatus();

            Assert.False(status.FirefoxProtocolRegistered);
            Assert.False(status.IsCompatible);
        }
        finally
        {
            Directory.Delete(root, recursive: true);
        }
    }

    [Fact]
    public async Task RejectsChromiumManifestWithoutStoredExpectedExtensionIdentity()
    {
        string root = Path.Combine(Path.GetTempPath(), $"xdm-host-{Guid.NewGuid():N}");
        string host = Path.Combine(root, "XDM.NativeHost");
        string app = Path.Combine(root, "XDM");
        Directory.CreateDirectory(root);
        await File.WriteAllTextAsync(host, "host");
        await File.WriteAllTextAsync(app, "app");
        try
        {
            BrowserHostInstaller installer = new(host, root, BrowserHostPlatform.Linux, applicationPath: app);
            await installer.RepairAsync("abcdefghijklmnopabcdefghijklmnop");
            string manifestPath = Path.Combine(
                root,
                ".config",
                "chromium",
                "NativeMessagingHosts",
                $"{BrowserHostInstaller.HostName}.json");
            await File.WriteAllTextAsync(
                manifestPath,
                JsonSerializer.Serialize(new
                {
                    name = BrowserHostInstaller.HostName,
                    path = host,
                    type = "stdio",
                    xdm_protocol_version = BrowserNativeProtocol.ProtocolVersion,
                    allowed_origins = new[] { "chrome-extension://abcdefghijklmnopabcdefghijklmnop/" }
                }));

            BrowserHostInstallationStatus status = installer.GetStatus();

            Assert.False(status.IsCompatible);
            Assert.Contains(status.Manifests!, static manifest => manifest.Browser == "Chromium" && !manifest.IsCompatible);
        }
        finally
        {
            Directory.Delete(root, recursive: true);
        }
    }
}
