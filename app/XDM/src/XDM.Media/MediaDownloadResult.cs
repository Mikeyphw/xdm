namespace XDM.Media;

public sealed record MediaDownloadResult(
    string DestinationPath,
    MediaKind Kind,
    int DownloadedFragments,
    long DownloadedBytes,
    TimeSpan Elapsed,
    bool UsedFfmpeg,
    IReadOnlyList<string> SubtitlePaths);
