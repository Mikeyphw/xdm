namespace XDM.Media;

public sealed record MediaDownloadProgress(
    string Stage,
    int CompletedFragments,
    int? TotalFragments,
    long DownloadedBytes,
    string Message)
{
    public double? Fraction => TotalFragments is > 0
        ? Math.Clamp((double)CompletedFragments / TotalFragments.Value, 0, 1)
        : null;
}
