using XDM.Core.Scheduling;

namespace XDM.Core.Abstractions;

public interface IAntivirusScanner
{
    bool IsAvailable(AntivirusScanSettings settings);

    Task<AntivirusScanResult> ScanAsync(
        string filePath,
        AntivirusScanSettings settings,
        CancellationToken cancellationToken = default);
}
