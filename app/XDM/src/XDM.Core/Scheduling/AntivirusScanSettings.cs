namespace XDM.Core.Scheduling;

public sealed record AntivirusScanSettings(
    bool Enabled,
    string? ExecutablePath,
    IReadOnlyList<string>? Arguments,
    int TimeoutSeconds = 120)
{
    public static AntivirusScanSettings Disabled { get; } = new(false, null, [], 120);

    public AntivirusScanSettings Normalize()
        => this with
        {
            ExecutablePath = string.IsNullOrWhiteSpace(ExecutablePath) ? null : ExecutablePath.Trim(),
            Arguments = Arguments?
                .Where(static argument => !string.IsNullOrWhiteSpace(argument))
                .Select(static argument => argument.Trim())
                .Take(64)
                .ToArray() ?? [],
            TimeoutSeconds = Math.Clamp(TimeoutSeconds, 5, 3600)
        };
}
