namespace XDM.BrowserIntegration;

public sealed record BrowserHostInstallationStatus(
    bool NativeHostExists,
    bool FirefoxManifestInstalled,
    int ChromiumManifestCount,
    string Message);
