namespace XDM.Diagnostics;

internal sealed record TransferHealthProbeOptions(
    TimeSpan TotalTimeout,
    TimeSpan StageTimeout,
    int DiskWriteBytes)
{
    public static TransferHealthProbeOptions Default { get; } = new(
        TimeSpan.FromSeconds(20),
        TimeSpan.FromSeconds(5),
        1024 * 1024);
}
