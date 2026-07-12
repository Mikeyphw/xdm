namespace XDM.BrowserIntegration;

public sealed record BrowserExtensionHealthReport(
    string Browser,
    string? BrowserVersion,
    string? ExtensionVersion,
    string? ExtensionId,
    int ManifestVersion,
    bool IncognitoAllowed,
    bool EnhancedAccessGranted,
    IReadOnlyList<string>? GrantedOrigins,
    string ProtocolVersion,
    string Compatibility,
    IReadOnlyList<string>? Capabilities,
    DateTimeOffset ReportedAtUtc)
{
    public void Validate()
    {
        if (string.IsNullOrWhiteSpace(Browser) || Browser.Length > 128
            || BrowserVersion is { Length: > 512 }
            || ExtensionVersion is { Length: > 128 }
            || ExtensionId is { Length: > 256 }
            || ManifestVersion is < 2 or > 3
            || !string.Equals(ProtocolVersion, BrowserNativeProtocol.ProtocolVersion, StringComparison.Ordinal)
            || Compatibility is not ("compatible" or "extension_outdated" or "host_outdated" or "protocol_mismatch"))
        {
            throw new InvalidDataException("Browser extension health report is invalid.");
        }

        if (GrantedOrigins is { Count: > 64 }
            || GrantedOrigins?.Any(static value => value is null || value.Length > 512) == true
            || Capabilities is { Count: > 64 }
            || Capabilities?.Any(static value => value is null || value.Length > 128) == true)
        {
            throw new InvalidDataException("Browser extension health report contains oversized collections.");
        }
    }
}
