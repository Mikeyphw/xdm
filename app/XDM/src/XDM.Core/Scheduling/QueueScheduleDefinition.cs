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
    ScheduleCompletionAction CompletionAction,
    string? BandwidthProfileId = null)
{
    public QueueScheduleDefinition Normalize(string fallbackQueueId)
    {
        WeekDays normalizedDays = Days & WeekDays.EveryDay;
        return this with
        {
            Id = string.IsNullOrWhiteSpace(Id) ? Guid.NewGuid().ToString("N") : Id.Trim(),
            Name = string.IsNullOrWhiteSpace(Name) ? "Schedule" : Name.Trim(),
            Enabled = Enabled && normalizedDays != WeekDays.None,
            QueueId = string.IsNullOrWhiteSpace(QueueId) ? fallbackQueueId : QueueId.Trim(),
            Days = normalizedDays,
            CompletionAction = (CompletionAction ?? ScheduleCompletionAction.None).Normalize(),
            BandwidthProfileId = string.IsNullOrWhiteSpace(BandwidthProfileId) ? null : BandwidthProfileId.Trim()
        };
    }
}
