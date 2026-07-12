namespace XDM.Core.Settings;

public sealed record ProxySettings(
    ProxyMode Mode,
    string? Host,
    int Port,
    string? Username,
    string? Password,
    bool BypassLocal,
    IReadOnlyList<string>? BypassList)
{
    public static ProxySettings SystemDefault { get; } = new(
        ProxyMode.System,
        null,
        8080,
        null,
        null,
        true,
        []);

    public ProxySettings Normalize()
    {
        string? host = string.IsNullOrWhiteSpace(Host) ? null : Host.Trim();
        ProxyMode mode = Mode;
        if (mode == ProxyMode.Manual && host is null)
        {
            mode = ProxyMode.None;
        }

        return this with
        {
            Mode = mode,
            Host = host,
            Port = Math.Clamp(Port, 1, 65535),
            Username = EmptyToNull(Username),
            Password = EmptyToNull(Password),
            BypassList = BypassList?
                .Select(static value => value.Trim())
                .Where(static value => value.Length > 0)
                .Distinct(StringComparer.OrdinalIgnoreCase)
                .Take(128)
                .ToArray() ?? []
        };
    }

    private static string? EmptyToNull(string? value)
        => string.IsNullOrWhiteSpace(value) ? null : value.Trim();
}
