namespace XDM.Core.Settings;

public sealed record ProxySettings(
    ProxyMode Mode,
    string? Host,
    int Port,
    string? Username,
    string? Password,
    bool BypassLocal,
    IReadOnlyList<string>? BypassList,
    string? AutomaticConfigurationUrl = null,
    ProxyAuthenticationMode AuthenticationMode = ProxyAuthenticationMode.Basic)
{
    public static ProxySettings SystemDefault { get; } = new(
        ProxyMode.System,
        null,
        8080,
        null,
        null,
        true,
        [],
        null,
        ProxyAuthenticationMode.Integrated);

    public ProxySettings Normalize()
    {
        string? host = EmptyToNull(Host);
        string? automaticUrl = NormalizeAutomaticUrl(AutomaticConfigurationUrl);
        ProxyMode mode = Mode;
        if (mode == ProxyMode.Manual && host is null)
        {
            mode = ProxyMode.None;
        }
        else if (mode == ProxyMode.AutomaticScript && automaticUrl is null)
        {
            mode = ProxyMode.System;
        }

        string? username = EmptyToNull(Username);
        ProxyAuthenticationMode authentication = AuthenticationMode;
        if (authentication == ProxyAuthenticationMode.Basic && username is null)
        {
            authentication = ProxyAuthenticationMode.None;
        }

        return this with
        {
            Mode = mode,
            Host = host,
            Port = Math.Clamp(Port, 1, 65535),
            Username = authentication == ProxyAuthenticationMode.Basic ? username : null,
            Password = authentication == ProxyAuthenticationMode.Basic ? EmptyToNull(Password) : null,
            BypassList = BypassList?
                .Select(static value => value.Trim())
                .Where(static value => value.Length > 0)
                .Distinct(StringComparer.OrdinalIgnoreCase)
                .Take(128)
                .ToArray() ?? [],
            AutomaticConfigurationUrl = automaticUrl,
            AuthenticationMode = authentication
        };
    }

    private static string? NormalizeAutomaticUrl(string? value)
    {
        if (string.IsNullOrWhiteSpace(value)
            || !Uri.TryCreate(value.Trim(), UriKind.Absolute, out Uri? uri)
            || uri.Scheme is not ("http" or "https" or "file"))
        {
            return null;
        }

        return uri.AbsoluteUri;
    }

    private static string? EmptyToNull(string? value)
        => string.IsNullOrWhiteSpace(value) ? null : value.Trim();
}
