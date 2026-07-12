namespace XDM.Media;

internal sealed record StreamDownloadResult(
    string Path,
    int FragmentCount,
    long DownloadedBytes,
    bool IsLive);
