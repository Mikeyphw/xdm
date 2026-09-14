using System.Text.Json;
using XDM.BrowserIntegration;

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
    public void FirefoxFixtureIsTheCanonicalAndroidExtension()
    {
        using JsonDocument document = LoadManifest("firefox-manifest.template.json");
        JsonElement root = document.RootElement;
        string[] required = root.GetProperty("permissions").EnumerateArray().Select(static value => value.GetString()!).ToArray();
        JsonElement gecko = root.GetProperty("browser_specific_settings").GetProperty("gecko");

        Assert.Equal(2, root.GetProperty("manifest_version").GetInt32());
        Assert.Equal(FirefoxExtensionHandoffParser.CanonicalExtensionId, gecko.GetProperty("id").GetString());
        Assert.Contains("storage", required);
        Assert.Contains("tabs", required);
        Assert.Contains("activeTab", required);
        Assert.Contains("webRequest", required);
        Assert.Contains("<all_urls>", required);
        Assert.DoesNotContain("nativeMessaging", required);
        Assert.DoesNotContain("downloads", required);
        Assert.False(root.TryGetProperty("optional_permissions", out _));
    }

    [Fact]
    public void CanonicalFirefoxHandoffKeepsAndroidSchemesAndFinalHeaderAuthority()
    {
        string source = File.ReadAllText(GetFixturePath("firefox-handoff.js"));

        Assert.Contains("buildXdmAdd", source, StringComparison.Ordinal);
        Assert.Contains("buildXdmCapture", source, StringComparison.Ordinal);
        Assert.Contains("buildCaptureSession", source, StringComparison.Ordinal);
        Assert.Contains("xdmdownload", source, StringComparison.Ordinal);
        Assert.Contains("proposedHeaders", source, StringComparison.Ordinal);
        Assert.Contains("finalHeaders", source, StringComparison.Ordinal);
        Assert.Contains("rawHeaders: finalHeaders", source, StringComparison.Ordinal);
    }

    [Fact]
    public void CanonicalFirefoxObserverKeepsExistingCapturePipeline()
    {
        string observer = File.ReadAllText(GetFixturePath("firefox-network-observer.js"));
        string detector = File.ReadAllText(GetFixturePath("firefox-detector-core.js"));

        Assert.Contains("webRequest", observer, StringComparison.Ordinal);
        Assert.Contains("XdmHandoffV1", observer, StringComparison.Ordinal);
        Assert.Contains("candidate", observer, StringComparison.OrdinalIgnoreCase);
        Assert.Contains("m3u8", detector, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public void ChromiumExtensionStillImplementsSiteModesPendingConfirmationAndSensitiveDefaults()
    {
        string source = File.ReadAllText(GetFixturePath("chrome-app.js"));

        Assert.Contains("defaultSiteMode", source, StringComparison.Ordinal);
        Assert.Contains("sitePolicies", source, StringComparison.Ordinal);
        Assert.Contains("site_requires_confirmation", source, StringComparison.Ordinal);
        Assert.Contains("accept-pending", source, StringComparison.Ordinal);
        Assert.Contains("accounts.google.com", source, StringComparison.Ordinal);
        Assert.Contains("permissions-changed", source, StringComparison.Ordinal);
        Assert.Contains("storage.session", source, StringComparison.Ordinal);
    }

    [Fact]
    public void ChromiumPopupExplainsOptionalMetadataAccess()
    {
        string source = File.ReadAllText(GetFixturePath("chrome-popup.html"));

        Assert.Contains("Grant enhanced metadata access", source, StringComparison.Ordinal);
        Assert.Contains("Ask each time", source, StringComparison.Ordinal);
        Assert.Contains("Waiting for confirmation", source, StringComparison.Ordinal);
    }

    [Fact]
    public void ChromiumExtensionRegistersWakeupListenersBeforeAwaitedStartup()
    {
        string source = File.ReadAllText(GetFixturePath("chrome-app.js"));

        Assert.True(source.IndexOf("registerDownloadTakeover();", StringComparison.Ordinal) < source.IndexOf("await this.loadRules();", StringComparison.Ordinal));
        Assert.True(source.IndexOf("registerRuntimeMessages();", StringComparison.Ordinal) < source.IndexOf("await this.loadRules();", StringComparison.Ordinal));
        Assert.Contains("loadRequestMetadata", source, StringComparison.Ordinal);
        Assert.Contains("browserRequestId", source, StringComparison.Ordinal);
    }

    [Fact]
    public void ChromiumNativeConnectorScalesBatchTimeoutBeyondPerItemBudget()
    {
        string source = File.ReadAllText(GetFixturePath("chrome-connector.js"));

        Assert.Contains("BATCH_ITEM_TIMEOUT_MS", source, StringComparison.Ordinal);
        Assert.Contains("timeoutFor(type, payload)", source, StringComparison.Ordinal);
    }

    private static JsonDocument LoadManifest(string name)
        => JsonDocument.Parse(File.ReadAllBytes(GetFixturePath(name)));

    private static string GetFixturePath(string name)
        => Path.Combine(AppContext.BaseDirectory, "Fixtures", "Extensions", name);
}
