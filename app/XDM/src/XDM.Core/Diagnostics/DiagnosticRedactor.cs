using System.Text.Json;
using System.Text.RegularExpressions;

namespace XDM.Core.Diagnostics;

public static partial class DiagnosticRedactor
{
    private static readonly string[] SecretQueryKeys =
    [
        "token",
        "access_token",
        "refresh_token",
        "id_token",
        "auth",
        "authorization",
        "key",
        "api_key",
        "apikey",
        "client_secret",
        "secret",
        "signature",
        "sig",
        "password",
        "passwd",
        "pwd",
        "x-amz-signature",
        "x-amz-credential",
        "x-amz-security-token"
    ];

    public static string Redact(string value)
    {
        ArgumentNullException.ThrowIfNull(value);
        string redacted = UriUserInfoRegex().Replace(value, "$1[REDACTED]@");
        redacted = HeaderSecretRegex().Replace(redacted, match =>
        {
            string key = match.Groups[1].Value;
            return match.Value.Contains(':')
                ? $"{key}: [REDACTED]"
                : $"{key}=[REDACTED]";
        });
        redacted = QuerySecretRegex().Replace(redacted, "$1[REDACTED]");
        redacted = WindowsPathRegex().Replace(redacted, static match => RedactPathLiteral(match.Value));
        redacted = UnixPathRegex().Replace(redacted, static match => RedactPathLiteral(match.Value));
        return redacted;
    }

    public static IReadOnlyDictionary<string, string?> RedactContext(
        IReadOnlyDictionary<string, string?>? context)
    {
        if (context is null || context.Count == 0)
        {
            return new Dictionary<string, string?>(StringComparer.Ordinal);
        }

        return context.ToDictionary(
            static pair => pair.Key,
            static pair => pair.Value is null ? null : Redact(pair.Value),
            StringComparer.Ordinal);
    }

    public static string RedactJson(string json)
        => Redact(json);

    public static async ValueTask WriteRedactedJsonAsync<T>(
        Stream stream,
        T value,
        JsonSerializerOptions jsonOptions,
        CancellationToken cancellationToken)
    {
        await using MemoryStream buffer = new();
        await JsonSerializer.SerializeAsync(buffer, value, jsonOptions, cancellationToken)
            .ConfigureAwait(false);
        buffer.Position = 0;
        using StreamReader reader = new(buffer, leaveOpen: true);
        string json = await reader.ReadToEndAsync(cancellationToken).ConfigureAwait(false);
        string redacted = RedactJson(json);
        await using StreamWriter writer = new(stream, leaveOpen: true);
        await writer.WriteAsync(redacted.AsMemory(), cancellationToken).ConfigureAwait(false);
        await writer.FlushAsync(cancellationToken).ConfigureAwait(false);
    }

    public static string RedactOrigin(Uri uri)
    {
        ArgumentNullException.ThrowIfNull(uri);
        if (!uri.IsAbsoluteUri)
        {
            return "invalid or unavailable";
        }

        string host = uri.IdnHost.Length == 0 ? uri.Host : uri.IdnHost;
        if (string.IsNullOrWhiteSpace(host))
        {
            return "invalid or unavailable";
        }

        string port = uri.IsDefaultPort
            ? string.Empty
            : ":" + uri.Port.ToString(System.Globalization.CultureInfo.InvariantCulture);
        return $"{uri.Scheme}://{host}{port}";
    }

    public static string RedactOrigin(string? value)
        => Uri.TryCreate(value, UriKind.Absolute, out Uri? uri)
            ? RedactOrigin(uri)
            : "invalid or unavailable";

    public static string RedactPath(string? value)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            return "unavailable";
        }

        string fileName = Path.GetFileName(value.Trim().Trim('"', '\'', '`'));
        return string.IsNullOrWhiteSpace(fileName)
            ? "[LOCAL-PATH]"
            : $"[LOCAL-PATH]/{fileName}";
    }

    private static string RedactPathLiteral(string value)
    {
        string suffix = string.Empty;
        string candidate = value;
        while (candidate.Length > 0 && candidate[^1] is '.' or ',' or ';' or ':' or ')' or ']' or '}')
        {
            suffix = candidate[^1] + suffix;
            candidate = candidate[..^1];
        }

        return RedactPath(candidate) + suffix;
    }

    public static bool IsSecretQueryKey(string key)
        => SecretQueryKeys.Any(candidate => string.Equals(candidate, key, StringComparison.OrdinalIgnoreCase));

    [GeneratedRegex("""(?im)\b(Authorization|Proxy-Authorization|Cookie|Set-Cookie|X-XDM-Token|X-Api-Key|Api-Key|Authentication|Password|Passwd|Client-Secret|Refresh-Token|Access-Token)\s*[:=]\s*[^\r\n"]*""")]
    private static partial Regex HeaderSecretRegex();

    [GeneratedRegex("""(?i)([?&](?:token|access_token|refresh_token|id_token|auth|authorization|key|api_key|apikey|client_secret|secret|signature|sig|password|passwd|pwd|x-amz-signature|x-amz-credential|x-amz-security-token)=)[^&#\s"']+""")]
    private static partial Regex QuerySecretRegex();

    [GeneratedRegex("""(?i)\b([a-z][a-z0-9+.-]*://)([^/@\s"'<>]+)@""")]
    private static partial Regex UriUserInfoRegex();

    [GeneratedRegex("""(?i)(?:[A-Z]:\\(?:[^\\/:*?"<>|\r\n]+\\)+[^\\/:*?"<>|\r\n]*)""")]
    private static partial Regex WindowsPathRegex();

    [GeneratedRegex("""(?i)/(?:home|users|mnt|media|var|tmp|data/data)/(?:[^\s"'<>]+/)*[^\s"'<>]*""")]
    private static partial Regex UnixPathRegex();
}
