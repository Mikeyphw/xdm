namespace XDM.BrowserIntegration;

public static class NativeHostOriginVerifier
{
    public static bool IsMatch(string? launchOrigin, string? extensionId)
    {
        if (string.IsNullOrWhiteSpace(launchOrigin) || string.IsNullOrWhiteSpace(extensionId))
        {
            return true;
        }

        string origin = launchOrigin.Trim().TrimEnd('/');
        string id = extensionId.Trim();
        if (origin.StartsWith("chrome-extension://", StringComparison.OrdinalIgnoreCase)
            || origin.StartsWith("moz-extension://", StringComparison.OrdinalIgnoreCase))
        {
            return string.Equals(origin[(origin.IndexOf("://", StringComparison.Ordinal) + 3)..], id, StringComparison.Ordinal);
        }

        // Firefox may pass the add-on ID rather than a moz-extension origin.
        return string.Equals(origin, id, StringComparison.Ordinal);
    }

    public static string? ResolveLaunchOrigin(string[] commandLineArguments)
        => commandLineArguments.Skip(1)
            .FirstOrDefault(static value =>
                value.StartsWith("chrome-extension://", StringComparison.OrdinalIgnoreCase)
                || value.StartsWith("moz-extension://", StringComparison.OrdinalIgnoreCase)
                || value.Contains('@'));
}
