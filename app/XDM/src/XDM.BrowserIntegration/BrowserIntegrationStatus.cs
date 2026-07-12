namespace XDM.BrowserIntegration;

public sealed record BrowserIntegrationStatus(
    bool IsListening,
    int Port,
    string ProtocolVersion,
    string AuthenticationToken,
    DateTimeOffset? StartedAt = null,
    DateTimeOffset? LastMessageAt = null,
    string? LastBrowser = null,
    string? LastCapturedUrl = null,
    string? LastError = null,
    DateTimeOffset? LastExtensionHealthAt = null,
    string? ExtensionBrowser = null,
    string? ExtensionBrowserVersion = null,
    string? ExtensionVersion = null,
    string? ExtensionId = null,
    int? ExtensionManifestVersion = null,
    bool? ExtensionIncognitoAllowed = null,
    bool? ExtensionEnhancedAccessGranted = null,
    IReadOnlyList<string>? ExtensionGrantedOrigins = null,
    string? ExtensionCompatibility = null,
    IReadOnlyList<string>? ExtensionCapabilities = null)
{
    public string Endpoint => $"http://127.0.0.1:{Port}";
}
