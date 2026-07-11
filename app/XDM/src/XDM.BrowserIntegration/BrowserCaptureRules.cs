namespace XDM.BrowserIntegration;

public sealed record BrowserCaptureRules(
    bool Enabled = true,
    DateTimeOffset? DisabledUntilUtc = null,
    bool CaptureIncognito = false,
    long MinimumSizeBytes = 0,
    IReadOnlyList<string>? AllowedMimeTypes = null,
    IReadOnlyList<string>? BlockedMimeTypes = null,
    IReadOnlyList<string>? AllowedExtensions = null,
    IReadOnlyList<string>? BlockedExtensions = null,
    IReadOnlyList<string>? IncludedSites = null,
    IReadOnlyList<string>? ExcludedSites = null)
{
    public void Validate()
    {
        if (MinimumSizeBytes < 0)
        {
            throw new InvalidDataException("Capture minimum size cannot be negative.");
        }

        ValidateList(AllowedMimeTypes, nameof(AllowedMimeTypes));
        ValidateList(BlockedMimeTypes, nameof(BlockedMimeTypes));
        ValidateList(AllowedExtensions, nameof(AllowedExtensions));
        ValidateList(BlockedExtensions, nameof(BlockedExtensions));
        ValidateList(IncludedSites, nameof(IncludedSites));
        ValidateList(ExcludedSites, nameof(ExcludedSites));
    }

    public BrowserCaptureRules Normalize()
        => this with
        {
            MinimumSizeBytes = Math.Max(0, MinimumSizeBytes),
            AllowedMimeTypes = NormalizeList(AllowedMimeTypes, lowerCase: true),
            BlockedMimeTypes = NormalizeList(BlockedMimeTypes, lowerCase: true),
            AllowedExtensions = NormalizeExtensions(AllowedExtensions),
            BlockedExtensions = NormalizeExtensions(BlockedExtensions),
            IncludedSites = NormalizeList(IncludedSites, lowerCase: true),
            ExcludedSites = NormalizeList(ExcludedSites, lowerCase: true)
        };

    private static string[] NormalizeExtensions(IReadOnlyList<string>? values)
        => NormalizeList(values, lowerCase: true)
            .Select(static value => value.TrimStart('.'))
            .Where(static value => value.Length > 0)
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToArray();

    private static string[] NormalizeList(IReadOnlyList<string>? values, bool lowerCase)
        => values?
            .Where(static value => !string.IsNullOrWhiteSpace(value))
            .Select(value => lowerCase ? value.Trim().ToLowerInvariant() : value.Trim())
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToArray()
            ?? [];

    private static void ValidateList(IReadOnlyList<string>? values, string name)
    {
        if (values is null)
        {
            return;
        }

        if (values.Count > 256)
        {
            throw new InvalidDataException($"Capture rule list '{name}' exceeds 256 entries.");
        }

        if (values.Any(static value => value is null
            || value.Length > 256
            || value.Any(static character => char.IsControl(character))))
        {
            throw new InvalidDataException($"Capture rule list '{name}' contains an invalid value.");
        }
    }
}
