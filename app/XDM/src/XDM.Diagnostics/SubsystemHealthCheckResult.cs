namespace XDM.Diagnostics;

public sealed record SubsystemHealthCheckResult(
    string Id,
    string Name,
    SubsystemHealthStatus Status,
    string Summary,
    string Details,
    TimeSpan Duration,
    string? RepairActionId = null,
    string? RepairLabel = null)
{
    public bool CanRepair => !string.IsNullOrWhiteSpace(RepairActionId);

    public string DurationText => Duration.TotalMilliseconds < 1
        ? "<1 ms"
        : $"{Duration.TotalMilliseconds:0} ms";
}
