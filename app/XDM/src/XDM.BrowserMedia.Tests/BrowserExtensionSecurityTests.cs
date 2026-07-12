using System.Text.Json;

namespace XDM.BrowserMedia.Tests;

public sealed class BrowserExtensionSecurityTests
{
    [Fact]
    public void ChromiumManifestUsesOptionalHostAndMetadataPermissions()
    {
        using JsonDocument document = LoadManifest("chrome-manifest.json");
        JsonElement root = document.RootElement;
        string[] required = root.GetProperty("permissions").EnumerateArray().Select(static value => value.GetString()!).ToArray();
        string[] optional = root.GetProperty("optional_permissions").EnumerateArray().Select(static value => value.GetString()!).ToArray();
        string[] origins = root.GetProperty("optional_host_permissions").EnumerateArray().Select(static value => value.GetString()!).ToArray();

        Assert.Equal(3, root.GetProperty("manifest_version").GetInt32());
        Assert.DoesNotContain("cookies", required);
        Assert.DoesNotContain("webRequest", required);
        Assert.Contains("cookies", optional);
        Assert.Contains("webRequest", optional);
        Assert.Contains("https://*/*", origins);
        Assert.False(root.TryGetProperty("host_permissions", out _));
    }

    [Fact]
    public void FirefoxManifestKeepsNetworkAccessOptional()
    {
        using JsonDocument document = LoadManifest("firefox-manifest.json");
        JsonElement root = document.RootElement;
        string[] required = root.GetProperty("permissions").EnumerateArray().Select(static value => value.GetString()!).ToArray();
        string[] optional = root.GetProperty("optional_permissions").EnumerateArray().Select(static value => value.GetString()!).ToArray();

        Assert.DoesNotContain("cookies", required);
        Assert.DoesNotContain("webRequest", required);
        Assert.DoesNotContain("https://*/*", required);
        Assert.Contains("cookies", optional);
        Assert.Contains("webRequest", optional);
        Assert.Contains("https://*/*", optional);
    }

    [Theory]
    [InlineData("chrome-app.js")]
    [InlineData("firefox-app.js")]
    public void ExtensionImplementsSiteModesPendingConfirmationAndSensitiveDefaults(string fixture)
    {
        string source = File.ReadAllText(GetFixturePath(fixture));

        Assert.Contains("defaultSiteMode", source, StringComparison.Ordinal);
        Assert.Contains("sitePolicies", source, StringComparison.Ordinal);
        Assert.Contains("site_requires_confirmation", source, StringComparison.Ordinal);
        Assert.Contains("accept-pending", source, StringComparison.Ordinal);
        Assert.Contains("accounts.google.com", source, StringComparison.Ordinal);
        Assert.Contains("permissions-changed", source, StringComparison.Ordinal);
        Assert.Contains("storage.session", source, StringComparison.Ordinal);
    }

    [Fact]
    public void PopupExplainsOptionalMetadataAccess()
    {
        string source = File.ReadAllText(GetFixturePath("chrome-popup.html"));

        Assert.Contains("Grant enhanced metadata access", source, StringComparison.Ordinal);
        Assert.Contains("Ask each time", source, StringComparison.Ordinal);
        Assert.Contains("Waiting for confirmation", source, StringComparison.Ordinal);
    }

    private static JsonDocument LoadManifest(string name)
        => JsonDocument.Parse(File.ReadAllBytes(GetFixturePath(name)));

    private static string GetFixturePath(string name)
        => Path.Combine(AppContext.BaseDirectory, "Fixtures", "Extensions", name);
}
