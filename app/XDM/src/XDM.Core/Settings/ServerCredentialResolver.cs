namespace XDM.Core.Settings;

public static class ServerCredentialResolver
{
    public static (string? Username, string? Password) Resolve(ApplicationSettings settings, Uri source)
    {
        ArgumentNullException.ThrowIfNull(settings);
        ArgumentNullException.ThrowIfNull(source);
        ServerCredentialDefinition? credential = settings.Credentials?
            .Select(static item => item.Normalize())
            .FirstOrDefault(item => item.Matches(source));
        return credential is null
            ? (null, null)
            : (credential.Username, credential.Password);
    }

    public static bool IsSameOrigin(Uri left, Uri right)
    {
        ArgumentNullException.ThrowIfNull(left);
        ArgumentNullException.ThrowIfNull(right);
        return string.Equals(left.Scheme, right.Scheme, StringComparison.OrdinalIgnoreCase)
            && string.Equals(left.IdnHost, right.IdnHost, StringComparison.OrdinalIgnoreCase)
            && EffectivePort(left) == EffectivePort(right);
    }

    private static int EffectivePort(Uri uri)
        => uri.IsDefaultPort
            ? uri.Scheme.ToLowerInvariant() switch
            {
                "http" => 80,
                "https" => 443,
                "ftp" => 21,
                _ => -1
            }
            : uri.Port;
}
