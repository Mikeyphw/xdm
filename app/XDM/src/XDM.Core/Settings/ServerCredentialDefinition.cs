namespace XDM.Core.Settings;

public sealed record ServerCredentialDefinition(
    string Host,
    string Username,
    string Password,
    bool IncludeSubdomains)
{
    public ServerCredentialDefinition Normalize()
        => this with
        {
            Host = Host.Trim().TrimStart('.').ToLowerInvariant(),
            Username = Username.Trim(),
            Password = Password ?? string.Empty
        };

    public bool Matches(Uri source)
    {
        ArgumentNullException.ThrowIfNull(source);
        string normalizedHost = Host.Trim().TrimStart('.');
        if (normalizedHost.Length == 0)
        {
            return false;
        }

        if (source.Host.Equals(normalizedHost, StringComparison.OrdinalIgnoreCase))
        {
            return true;
        }

        return IncludeSubdomains
            && source.Host.EndsWith($".{normalizedHost}", StringComparison.OrdinalIgnoreCase);
    }
}
