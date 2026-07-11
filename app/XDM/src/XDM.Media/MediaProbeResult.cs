namespace XDM.Media;

public sealed record MediaProbeResult(
    Uri Source,
    MediaKind Kind,
    string? ContentType,
    int VariantCount,
    string? SuggestedFileName,
    string Description)
{
    public bool IsMedia => Kind != MediaKind.Unknown;
}
