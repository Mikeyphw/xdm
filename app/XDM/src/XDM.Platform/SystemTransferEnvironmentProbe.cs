using System.ComponentModel;
using System.Diagnostics;
using System.Net.NetworkInformation;
using XDM.Core.Policies;

namespace XDM.Platform;

public sealed class SystemTransferEnvironmentProbe : ITransferEnvironmentProbe
{
    private readonly ITransferEnvironmentDetector _detector;

    public SystemTransferEnvironmentProbe()
        : this(SystemTransferEnvironmentDetector.Instance)
    {
    }

    public SystemTransferEnvironmentProbe(ITransferEnvironmentDetector detector)
    {
        _detector = detector ?? throw new ArgumentNullException(nameof(detector));
    }

    public Task<TransferEnvironmentSnapshot> GetSnapshotAsync(CancellationToken cancellationToken = default)
    {
        cancellationToken.ThrowIfCancellationRequested();
        bool networkAvailable = _detector.IsNetworkAvailable();
        bool meteredOverridePresent = TryReadBooleanEnvironment("XDM_NETWORK_METERED", out bool? meteredOverride);
        bool batteryOverridePresent = TryReadBooleanEnvironment("XDM_ON_BATTERY", out bool? batteryOverride);
        bool? metered = meteredOverridePresent
            ? meteredOverride
            : _detector.DetectMeteredNetwork(cancellationToken);
        bool? onBattery = batteryOverridePresent
            ? batteryOverride
            : _detector.DetectOnBattery(cancellationToken);
        string source = BuildSource(meteredOverridePresent, batteryOverridePresent, metered, onBattery);
        return Task.FromResult(new TransferEnvironmentSnapshot(
            networkAvailable,
            metered,
            onBattery,
            source,
            DateTimeOffset.UtcNow));
    }

    private static string BuildSource(
        bool meteredOverridePresent,
        bool batteryOverridePresent,
        bool? metered,
        bool? onBattery)
    {
        List<string> parts = [];
        parts.Add(meteredOverridePresent ? "metered override" : MeteredSourceDescription(metered));
        parts.Add(batteryOverridePresent ? "battery override" : PowerSourceDescription(onBattery));
        return string.Join("; ", parts);
    }

    private static string MeteredSourceDescription(bool? metered)
        => metered is null
            ? "network cost unknown"
            : "system network cost";

    private static string PowerSourceDescription(bool? onBattery)
        => onBattery is null
            ? "power source unknown"
            : "system power source";

    private static bool TryReadBooleanEnvironment(string name, out bool? parsed)
    {
        string? value = Environment.GetEnvironmentVariable(name);
        parsed = value?.Trim().ToLowerInvariant() switch
        {
            "1" or "true" or "yes" or "on" => true,
            "0" or "false" or "no" or "off" => false,
            _ => null
        };
        return value is not null;
    }
}

public interface ITransferEnvironmentDetector
{
    bool IsNetworkAvailable();

    bool? DetectMeteredNetwork(CancellationToken cancellationToken = default);

    bool? DetectOnBattery(CancellationToken cancellationToken = default);
}

public sealed class SystemTransferEnvironmentDetector : ITransferEnvironmentDetector
{
    private static readonly TimeSpan DetectionCommandTimeout = TimeSpan.FromSeconds(2);

    public static SystemTransferEnvironmentDetector Instance { get; } = new();

    public bool IsNetworkAvailable()
        => NetworkInterface.GetIsNetworkAvailable();

    public bool? DetectMeteredNetwork(CancellationToken cancellationToken = default)
    {
        cancellationToken.ThrowIfCancellationRequested();
        if (OperatingSystem.IsLinux())
        {
            return DetectLinuxMeteredNetwork(cancellationToken);
        }

        if (OperatingSystem.IsWindows())
        {
            return DetectWindowsMeteredNetwork(cancellationToken);
        }

        return null;
    }

    public bool? DetectOnBattery(CancellationToken cancellationToken = default)
    {
        cancellationToken.ThrowIfCancellationRequested();
        if (OperatingSystem.IsLinux())
        {
            return DetectLinuxBattery();
        }

        if (OperatingSystem.IsWindows())
        {
            return DetectWindowsBattery(cancellationToken);
        }

        return null;
    }

    private static bool? DetectLinuxMeteredNetwork(CancellationToken cancellationToken)
    {
        CommandProbeResult result = RunCommand(
            "nmcli",
            ["-t", "-f", "GENERAL.METERED", "device", "show"],
            DetectionCommandTimeout,
            cancellationToken);
        if (!result.Succeeded)
        {
            return null;
        }

        bool sawUnknown = false;
        foreach (string line in result.Output.Split('\n', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
        {
            int separator = line.IndexOf(':', StringComparison.Ordinal);
            string value = separator >= 0 ? line[(separator + 1)..] : line;
            bool? parsed = ParseNetworkMeteredValue(value);
            if (parsed == true)
            {
                return true;
            }

            if (parsed is null)
            {
                sawUnknown = true;
            }
        }

        return sawUnknown ? null : false;
    }

    private static bool? DetectWindowsMeteredNetwork(CancellationToken cancellationToken)
    {
        const string script = "[Windows.Networking.Connectivity.NetworkInformation,Windows.Networking.Connectivity,ContentType=WindowsRuntime] | Out-Null; "
            + "$p=[Windows.Networking.Connectivity.NetworkInformation]::GetInternetConnectionProfile(); "
            + "if($null -eq $p){'unknown'}else{$p.GetConnectionCost().NetworkCostType.ToString()}";
        CommandProbeResult result = RunCommand(
            "powershell.exe",
            ["-NoProfile", "-NonInteractive", "-Command", script],
            DetectionCommandTimeout,
            cancellationToken);
        return result.Succeeded ? ParseNetworkMeteredValue(result.Output) : null;
    }

    private static bool? DetectWindowsBattery(CancellationToken cancellationToken)
    {
        const string script = "$b=Get-CimInstance -ClassName Win32_Battery -ErrorAction SilentlyContinue | Select-Object -First 1; "
            + "if($null -eq $b){'none'}else{$b.BatteryStatus}";
        CommandProbeResult result = RunCommand(
            "powershell.exe",
            ["-NoProfile", "-NonInteractive", "-Command", script],
            DetectionCommandTimeout,
            cancellationToken);
        if (!result.Succeeded)
        {
            return null;
        }

        string value = result.Output.Trim();
        if (string.Equals(value, "none", StringComparison.OrdinalIgnoreCase))
        {
            return false;
        }

        return value switch
        {
            "1" => true,
            "2" => false,
            "3" => false,
            "6" => false,
            "7" => false,
            "8" => false,
            "9" => false,
            _ => null
        };
    }

    private static bool? ParseNetworkMeteredValue(string value)
    {
        string normalized = value.Trim().ToLowerInvariant();
        if (normalized.Length == 0)
        {
            return null;
        }

        if (normalized.Contains("variable", StringComparison.Ordinal)
            || normalized.Contains("fixed", StringComparison.Ordinal)
            || normalized is "yes" or "metered" or "2" or "3")
        {
            return true;
        }

        if (normalized.Contains("unrestricted", StringComparison.Ordinal)
            || normalized is "no" or "unmetered" or "0" or "1")
        {
            return false;
        }

        return null;
    }

    private static bool? DetectLinuxBattery()
    {
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

    private static CommandProbeResult RunCommand(
        string fileName,
        IReadOnlyList<string> arguments,
        TimeSpan timeout,
        CancellationToken cancellationToken)
    {
        using CancellationTokenSource timeoutCancellation = new(timeout);
        using CancellationTokenSource linkedCancellation = CancellationTokenSource.CreateLinkedTokenSource(
            cancellationToken,
            timeoutCancellation.Token);
        Process? process = null;
        try
        {
            ProcessStartInfo startInfo = new(fileName)
            {
                UseShellExecute = false,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                CreateNoWindow = true
            };
            foreach (string argument in arguments)
            {
                startInfo.ArgumentList.Add(argument);
            }

            process = Process.Start(startInfo) ?? throw new InvalidOperationException("Process did not start.");
            Task<string> output = process.StandardOutput.ReadToEndAsync(linkedCancellation.Token);
            Task<string> error = process.StandardError.ReadToEndAsync(linkedCancellation.Token);
            process.WaitForExitAsync(linkedCancellation.Token).GetAwaiter().GetResult();
            string standardOutput = output.GetAwaiter().GetResult();
            _ = error.GetAwaiter().GetResult();
            return new CommandProbeResult(process.ExitCode == 0, standardOutput);
        }
        catch (OperationCanceledException) when (timeoutCancellation.IsCancellationRequested && !cancellationToken.IsCancellationRequested)
        {
            TryKillProcessTree(process);
            return new CommandProbeResult(false, string.Empty);
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            TryKillProcessTree(process);
            throw;
        }
        catch (Exception exception) when (exception is Win32Exception
            or InvalidOperationException
            or IOException
            or UnauthorizedAccessException)
        {
            return new CommandProbeResult(false, string.Empty);
        }
        finally
        {
            process?.Dispose();
        }
    }

    private static void TryKillProcessTree(Process? process)
    {
        if (process is null)
        {
            return;
        }

        try
        {
            if (!process.HasExited)
            {
                process.Kill(entireProcessTree: true);
            }
        }
        catch (Exception exception) when (exception is InvalidOperationException or Win32Exception or NotSupportedException)
        {
        }
    }

    private sealed record CommandProbeResult(bool Succeeded, string Output);
}
