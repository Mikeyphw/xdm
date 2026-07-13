using System.Net.NetworkInformation;
using XDM.Core.Policies;

namespace XDM.Platform;

public sealed class SystemTransferEnvironmentProbe : ITransferEnvironmentProbe
{
    public Task<TransferEnvironmentSnapshot> GetSnapshotAsync(CancellationToken cancellationToken = default)
    {
        cancellationToken.ThrowIfCancellationRequested();
        bool networkAvailable = NetworkInterface.GetIsNetworkAvailable();
        bool? metered = ParseBooleanEnvironment("XDM_NETWORK_METERED");
        bool? onBattery = ParseBooleanEnvironment("XDM_ON_BATTERY") ?? DetectLinuxBattery();
        string source = metered is not null || Environment.GetEnvironmentVariable("XDM_ON_BATTERY") is not null
            ? "Environment override"
            : OperatingSystem.IsLinux()
                ? "System network and Linux power state"
                : "System network state";
        return Task.FromResult(new TransferEnvironmentSnapshot(
            networkAvailable,
            metered,
            onBattery,
            source,
            DateTimeOffset.UtcNow));
    }

    private static bool? ParseBooleanEnvironment(string name)
    {
        string? value = Environment.GetEnvironmentVariable(name);
        return value?.Trim().ToLowerInvariant() switch
        {
            "1" or "true" or "yes" or "on" => true,
            "0" or "false" or "no" or "off" => false,
            _ => null
        };
    }

    private static bool? DetectLinuxBattery()
    {
        if (!OperatingSystem.IsLinux())
        {
            return null;
        }

        const string powerRoot = "/sys/class/power_supply";
        if (!Directory.Exists(powerRoot))
        {
            return null;
        }

        try
        {
            foreach (string directory in Directory.EnumerateDirectories(powerRoot))
            {
                string typePath = Path.Combine(directory, "type");
                if (!File.Exists(typePath)
                    || !string.Equals(File.ReadAllText(typePath).Trim(), "Battery", StringComparison.OrdinalIgnoreCase))
                {
                    continue;
                }

                string statusPath = Path.Combine(directory, "status");
                if (!File.Exists(statusPath))
                {
                    continue;
                }

                string status = File.ReadAllText(statusPath).Trim();
                return status.Equals("Discharging", StringComparison.OrdinalIgnoreCase);
            }
        }
        catch (IOException)
        {
        }
        catch (UnauthorizedAccessException)
        {
        }

        return null;
    }
}
