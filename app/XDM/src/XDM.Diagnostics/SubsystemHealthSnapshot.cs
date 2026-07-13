namespace XDM.Diagnostics;

public sealed record SubsystemHealthSnapshot(
    DateTimeOffset GeneratedAt,
    IReadOnlyList<SubsystemHealthCheckResult> Checks)
{
    public static SubsystemHealthSnapshot Empty { get; } = new(DateTimeOffset.MinValue, []);

    public int ProblemCount => Checks.Count(static check =>
        check.Status is SubsystemHealthStatus.Degraded or SubsystemHealthStatus.Unavailable);
}
