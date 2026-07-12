namespace XDM.BrowserIntegration;

public static class BrowserSiteCaptureModes
{
    public const string Always = "always";
    public const string Ask = "ask";
    public const string Never = "never";

    public static bool IsValid(string? value)
        => value is Always or Ask or Never;
}

public sealed record BrowserSiteCapturePolicy(string Pattern, string Mode)
{
    public BrowserSiteCapturePolicy Normalize()
        => new(
            Pattern.Trim().TrimStart('*').TrimStart('.').ToLowerInvariant(),
            Mode.Trim().ToLowerInvariant());
}

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
    IReadOnlyList<string>? ExcludedSites = null,
    string DefaultSiteMode = BrowserSiteCaptureModes.Always,
    IReadOnlyList<BrowserSiteCapturePolicy>? SitePolicies = null)
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

        if (!BrowserSiteCaptureModes.IsValid(DefaultSiteMode?.Trim().ToLowerInvariant()))
        {
            throw new InvalidDataException("Default site capture mode is invalid.");
        }

        if (SitePolicies is { Count: > 256 })
        {
            throw new InvalidDataException("Site policy list exceeds 256 entries.");
        }

        foreach (BrowserSiteCapturePolicy policy in SitePolicies ?? [])
        {
            BrowserSiteCapturePolicy normalized = policy.Normalize();
            if (normalized.Pattern.Length is 0 or > 253
                || normalized.Pattern.Any(static character => !(char.IsAsciiLetterOrDigit(character) || character is '.' or '-'))
                || !BrowserSiteCaptureModes.IsValid(normalized.Mode))
            {
                throw new InvalidDataException("Site capture policy contains an invalid pattern or mode.");
            }
        }
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
            ExcludedSites = NormalizeList(ExcludedSites, lowerCase: true),
            DefaultSiteMode = NormalizeMode(DefaultSiteMode),
            SitePolicies = NormalizePolicies(SitePolicies)
        };

    public string ResolveSiteMode(string host)
    {
        string normalizedHost = host.Trim().TrimEnd('.').ToLowerInvariant();
        BrowserSiteCapturePolicy? policy = SitePolicies?
            .Select(static value => value.Normalize())
            .Where(value => MatchesSite(normalizedHost, value.Pattern))
            .OrderByDescending(static value => value.Pattern.Length)
            .FirstOrDefault();
        return policy?.Mode ?? DefaultSiteMode;
    }


    private static string NormalizeMode(string? value)
    {
        string normalized = value?.Trim().ToLowerInvariant() ?? string.Empty;
        return BrowserSiteCaptureModes.IsValid(normalized)
            ? normalized
            : BrowserSiteCaptureModes.Always;
    }

    private static BrowserSiteCapturePolicy[] NormalizePolicies(IReadOnlyList<BrowserSiteCapturePolicy>? values)
        => values?
            .Where(static value => value is not null)
            .Select(static value => value.Normalize())
            .Where(static value => value.Pattern.Length > 0 && BrowserSiteCaptureModes.IsValid(value.Mode))
            .GroupBy(static value => value.Pattern, StringComparer.OrdinalIgnoreCase)
            .Select(static group => group.Last())
            .Take(256)
            .ToArray()
            ?? [];

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
            .Take(256)
            .ToArray()
            ?? [];

    private static void ValidateList(IReadOnlyList<string>? values, string name)
    {
        if (values is { Count: > 256 })
        {
            throw new InvalidDataException($"{name} exceeds 256 entries.");
        }

        if (values?.Any(static value => value is null || value.Length > 512) == true)
        {
            throw new InvalidDataException($"{name} contains an invalid entry.");
        }
    }

    private static bool MatchesSite(string host, string pattern)
        => string.Equals(host, pattern, StringComparison.OrdinalIgnoreCase)
            || host.EndsWith($".{pattern}", StringComparison.OrdinalIgnoreCase);
}
