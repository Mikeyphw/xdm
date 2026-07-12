namespace XDM.Core.Scheduling;

public sealed record SchedulerRuntimeState(
    DateTimeOffset LastEvaluationUtc,
    IReadOnlyDictionary<string, DateTimeOffset> LastStartedWindows)
{
    public static SchedulerRuntimeState Empty { get; } = new(
        DateTimeOffset.MinValue,
        new Dictionary<string, DateTimeOffset>(StringComparer.Ordinal));
}
