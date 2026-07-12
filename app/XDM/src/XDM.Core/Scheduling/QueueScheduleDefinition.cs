namespace XDM.Core.Scheduling;

public sealed record QueueScheduleDefinition(
    string Id,
    string Name,
    bool Enabled,
    string QueueId,
    TimeOnly StartTime,
    TimeOnly EndTime,
    WeekDays Days,
    MissedRunPolicy MissedRunPolicy,
    ScheduleCompletionAction CompletionAction)
{
    public QueueScheduleDefinition Normalize(string fallbackQueueId)
        => this with
        {
            Id = string.IsNullOrWhiteSpace(Id) ? Guid.NewGuid().ToString("N") : Id.Trim(),
            Name = string.IsNullOrWhiteSpace(Name) ? "Schedule" : Name.Trim(),
            QueueId = string.IsNullOrWhiteSpace(QueueId) ? fallbackQueueId : QueueId.Trim(),
            Days = Days == WeekDays.None ? WeekDays.EveryDay : Days,
            CompletionAction = (CompletionAction ?? ScheduleCompletionAction.None).Normalize()
        };
}
