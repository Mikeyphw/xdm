namespace XDM.Core.Scheduling;

public sealed record SchedulerRuntimeSnapshot(
    DateTimeOffset UpdatedAt,
    DateTimeOffset? NextEvaluationAt,
    IReadOnlySet<string> ActiveScheduleIds,
    string StatusMessage,
    PendingCompletionAction? PendingAction)
{
    public static SchedulerRuntimeSnapshot Empty { get; } = new(
        DateTimeOffset.UtcNow,
        null,
        new HashSet<string>(StringComparer.Ordinal),
        "Scheduler is not running.",
        null);
}

public sealed record PendingCompletionAction(
    string ScheduleId,
    string ScheduleName,
    ScheduleCompletionActionKind Kind,
    DateTimeOffset ExecuteAt,
    int RemainingSeconds,
    string Message);
