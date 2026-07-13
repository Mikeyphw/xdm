using XDM.Core.Downloads;

namespace XDM.Core.Settings;

public sealed record OrganizationSettings(
    DuplicateUrlBehavior DuplicateUrlBehavior,
    bool ComputeContentHashes,
    IReadOnlyList<DestinationRuleDefinition> DestinationRules,
    IReadOnlyList<SavedSearchDefinition> SavedSearches)
{
    public static OrganizationSettings Default { get; } = new(
        DuplicateUrlBehavior.FocusExisting,
        false,
        [],
        [
            new SavedSearchDefinition("active", "Active", "status:active archived:false"),
            new SavedSearchDefinition("failed", "Needs attention", "status:failed archived:false"),
            new SavedSearchDefinition("duplicates", "Duplicates", "duplicate:true"),
            new SavedSearchDefinition("missing", "Missing files", "missing:true"),
            new SavedSearchDefinition("archive", "Archive", "archived:true")
        ]);

    public OrganizationSettings Normalize()
        => this with
        {
            DuplicateUrlBehavior = Enum.IsDefined(DuplicateUrlBehavior)
                ? DuplicateUrlBehavior
                : XDM.Core.Downloads.DuplicateUrlBehavior.FocusExisting,
            DestinationRules = DestinationRules?
                .Select(static rule => rule.Normalize())
                .Where(static rule => rule.Id.Length > 0 && rule.DestinationDirectory.Length > 0)
                .DistinctBy(static rule => rule.Id, StringComparer.Ordinal)
                .OrderBy(static rule => rule.Priority)
                .Take(128)
                .ToArray() ?? [],
            SavedSearches = SavedSearches?
                .Select(static search => search.Normalize())
                .Where(static search => search.Id.Length > 0 && search.Query.Length > 0)
                .DistinctBy(static search => search.Id, StringComparer.Ordinal)
                .Take(64)
                .ToArray() ?? []
        };
}

public sealed record DestinationRuleDefinition(
    string Id,
    string Name,
    bool Enabled,
    int Priority,
    string DestinationDirectory,
    string? HostSuffix = null,
    string? UrlContains = null,
    IReadOnlyList<string>? Extensions = null,
    string? CategoryId = null,
    IReadOnlyList<string>? Tags = null)
{
    public DestinationRuleDefinition Normalize()
        => this with
        {
            Id = Id.Trim(),
            Name = string.IsNullOrWhiteSpace(Name) ? Id.Trim() : Name.Trim(),
            Priority = Math.Clamp(Priority, 0, 10_000),
            DestinationDirectory = DestinationDirectory.Trim(),
            HostSuffix = NormalizeOptional(HostSuffix)?.TrimStart('.').ToLowerInvariant(),
            UrlContains = NormalizeOptional(UrlContains),
            Extensions = Extensions?
                .Select(static extension => extension.Trim().TrimStart('.').ToLowerInvariant())
                .Where(static extension => extension.Length > 0)
                .Distinct(StringComparer.OrdinalIgnoreCase)
                .Take(64)
                .ToArray() ?? [],
            CategoryId = NormalizeOptional(CategoryId),
            Tags = DownloadMetadata.NormalizeTags(Tags)
        };

    public bool Matches(Uri source, string fileName)
    {
        DestinationRuleDefinition rule = Normalize();
        if (!rule.Enabled)
        {
            return false;
        }

        if (rule.HostSuffix is { Length: > 0 }
            && !source.Host.Equals(rule.HostSuffix, StringComparison.OrdinalIgnoreCase)
            && !source.Host.EndsWith($".{rule.HostSuffix}", StringComparison.OrdinalIgnoreCase))
        {
            return false;
        }

        if (rule.UrlContains is { Length: > 0 }
            && !source.AbsoluteUri.Contains(rule.UrlContains, StringComparison.OrdinalIgnoreCase))
        {
            return false;
        }

        if (rule.Extensions is { Count: > 0 })
        {
            string extension = Path.GetExtension(fileName).TrimStart('.');
            if (!rule.Extensions.Contains(extension, StringComparer.OrdinalIgnoreCase))
            {
                return false;
            }
        }

        return rule.HostSuffix is { Length: > 0 }
            || rule.UrlContains is { Length: > 0 }
            || rule.Extensions is { Count: > 0 };
    }

    private static string? NormalizeOptional(string? value)
        => string.IsNullOrWhiteSpace(value) ? null : value.Trim();
}

public sealed record SavedSearchDefinition(string Id, string Name, string Query)
{
    public SavedSearchDefinition Normalize()
        => this with
        {
            Id = Id.Trim(),
            Name = string.IsNullOrWhiteSpace(Name) ? Id.Trim() : Name.Trim(),
            Query = Query.Trim()
        };
}
