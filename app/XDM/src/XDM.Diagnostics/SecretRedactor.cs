using System.Text.RegularExpressions;

namespace XDM.Diagnostics;

public static partial class SecretRedactor
{
    public static string Redact(string value)
    {
        ArgumentNullException.ThrowIfNull(value);
        string redacted = BearerSecretRegex().Replace(value, "$1[REDACTED]");
        redacted = HeaderSecretRegex().Replace(redacted, "$1[REDACTED]");
        return QuerySecretRegex().Replace(redacted, "$1[REDACTED]");
    }

    [GeneratedRegex("(?i)(authorization\\s*[:=]\\s*|cookie\\s*[:=]\\s*|password\\s*[:=]\\s*|x-xdm-token\\s*[:=]\\s*)[^\\s,;]+")]
    private static partial Regex HeaderSecretRegex();

    [GeneratedRegex("(?i)([?&](?:token|key|auth|signature|sig|password)=)[^&#\\s]+")]
    private static partial Regex QuerySecretRegex();

    [GeneratedRegex("(?i)(bearer\\s+)[A-Za-z0-9._~+/-]+=*")]
    private static partial Regex BearerSecretRegex();
}
