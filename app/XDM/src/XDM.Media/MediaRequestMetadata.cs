namespace XDM.Media;

public sealed record MediaRequestMetadata(
    IReadOnlyDictionary<string, string>? Headers = null,
    string? Cookie = null,
    string? Referer = null,
    string? UserAgent = null)
{
    public static MediaRequestMetadata Empty { get; } = new();
}
