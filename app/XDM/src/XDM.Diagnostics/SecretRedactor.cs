using XDM.Core.Diagnostics;

namespace XDM.Diagnostics;

public static class SecretRedactor
{
    public static string Redact(string value)
        => DiagnosticRedactor.Redact(value);

    public static string RedactOrigin(Uri uri)
        => DiagnosticRedactor.RedactOrigin(uri);

    public static string RedactOrigin(string? value)
        => DiagnosticRedactor.RedactOrigin(value);

    public static string RedactPath(string? value)
        => DiagnosticRedactor.RedactPath(value);

    public static IReadOnlyDictionary<string, string?> RedactContext(
        IReadOnlyDictionary<string, string?>? context)
        => DiagnosticRedactor.RedactContext(context);
}
