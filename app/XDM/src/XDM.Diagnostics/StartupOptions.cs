namespace XDM.Diagnostics;

public sealed record StartupOptions(bool SafeMode, bool ResetWindowState)
{
    public static StartupOptions Default { get; } = new(false, false);

    public static StartupOptions Parse(IEnumerable<string> arguments)
    {
        ArgumentNullException.ThrowIfNull(arguments);
        HashSet<string> values = new(arguments, StringComparer.OrdinalIgnoreCase);
        return new StartupOptions(
            values.Contains("--safe-mode"),
            values.Contains("--reset-window-state"));
    }
}
