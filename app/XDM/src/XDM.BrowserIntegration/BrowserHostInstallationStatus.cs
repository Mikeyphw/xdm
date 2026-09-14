namespace XDM.BrowserIntegration;

public sealed record BrowserHostManifestStatus(
    string Browser,
    string ManifestPath,
    bool Exists,
    bool IsCompatible,
    string Message);

public sealed record BrowserHostInstallationStatus(
    bool NativeHostExists,
    bool FirefoxProtocolRegistered,
    int ChromiumManifestCount,
    string Message,
    bool IsCompatible = false,
    int CompatibleManifestCount = 0,
    IReadOnlyList<BrowserHostManifestStatus>? Manifests = null);
