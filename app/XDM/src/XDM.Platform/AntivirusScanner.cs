using XDM.Core.Abstractions;
using XDM.Core.Scheduling;

namespace XDM.Platform;

public sealed class AntivirusScanner(IPlatformCommandRunner runner) : IAntivirusScanner
{
    private const string FilePlaceholder = "{file}";

    public bool IsAvailable(AntivirusScanSettings settings)
    {
        ArgumentNullException.ThrowIfNull(settings);
        AntivirusScanSettings normalized = settings.Normalize();
        return normalized.Enabled
            && normalized.ExecutablePath is not null
            && Path.IsPathFullyQualified(normalized.ExecutablePath)
            && File.Exists(normalized.ExecutablePath);
    }

    public async Task<AntivirusScanResult> ScanAsync(
        string filePath,
        AntivirusScanSettings settings,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(filePath);
        ArgumentNullException.ThrowIfNull(settings);
        AntivirusScanSettings normalized = settings.Normalize();
        if (!File.Exists(filePath))
        {
            return new AntivirusScanResult(filePath, false, null, "The completed file no longer exists.");
        }

        if (!IsAvailable(normalized) || normalized.ExecutablePath is null)
        {
            return new AntivirusScanResult(filePath, false, null, "The configured antivirus executable is unavailable.");
        }

        IReadOnlyList<string> configuredArguments = normalized.Arguments ?? [];
        List<string> arguments = new(configuredArguments.Count + 1);
        bool replaced = false;
        foreach (string argument in configuredArguments)
        {
            if (argument.Contains(FilePlaceholder, StringComparison.Ordinal))
            {
                arguments.Add(argument.Replace(FilePlaceholder, filePath, StringComparison.Ordinal));
                replaced = true;
            }
            else
            {
                arguments.Add(argument);
            }
        }

        if (!replaced)
        {
            arguments.Add(filePath);
        }

        PlatformCommandResult result = await runner
            .RunAsync(
                normalized.ExecutablePath,
                arguments,
                TimeSpan.FromSeconds(normalized.TimeoutSeconds),
                cancellationToken)
            .ConfigureAwait(false);
        bool succeeded = !result.TimedOut && result.ExitCode == 0;
        string message = result.TimedOut
            ? "Antivirus scan timed out."
            : succeeded
                ? "Antivirus scan completed successfully."
                : $"Antivirus scan exited with code {result.ExitCode}.";
        return new AntivirusScanResult(filePath, succeeded, result.ExitCode, message);
    }
}
