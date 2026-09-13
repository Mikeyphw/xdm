namespace XDM.DownloadEngine.Aria2;

public sealed record Aria2RuntimeOptions(
    long SpeedLimitBytesPerSecond,
    int ConnectionCount,
    bool ContinueDownloads,
    bool CheckCertificate)
{
    public Aria2RuntimeOptions Normalize() => this with
    {
        SpeedLimitBytesPerSecond = Math.Max(0, SpeedLimitBytesPerSecond),
        ConnectionCount = Math.Clamp(ConnectionCount, 1, 64)
    };
}
