namespace XDM.BrowserIntegration;

public interface IBrowserHostInstaller
{
    BrowserHostInstallationStatus GetStatus();

    Task<BrowserHostInstallationStatus> RepairAsync(
        string? chromiumExtensionId,
        CancellationToken cancellationToken = default);
}
