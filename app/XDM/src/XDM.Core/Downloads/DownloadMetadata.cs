namespace XDM.Core.Downloads;

public static class DownloadMetadata
{
    private static readonly char[] TagSeparators = [',', ';', '\n'];
    public static string[] NormalizeTags(IEnumerable<string>? tags)
        => tags?
            .Select(static tag => tag.Trim().TrimStart('#'))
            .Where(static tag => tag.Length > 0)
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .Take(32)
            .ToArray() ?? [];

    public static string[] ParseTags(string? value)
        => NormalizeTags(value?.Split(TagSeparators, StringSplitOptions.RemoveEmptyEntries));

    public static string NormalizeSourceIdentity(Uri source)
    {
        ArgumentNullException.ThrowIfNull(source);
        UriBuilder builder = new(source)
        {
            Fragment = string.Empty,
            Host = source.Host.ToLowerInvariant()
        };
        if ((builder.Scheme == Uri.UriSchemeHttp && builder.Port == 80)
            || (builder.Scheme == Uri.UriSchemeHttps && builder.Port == 443))
        {
            builder.Port = -1;
        }

        return builder.Uri.AbsoluteUri;
    }
}
