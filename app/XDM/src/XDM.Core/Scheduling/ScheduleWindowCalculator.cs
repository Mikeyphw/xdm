namespace XDM.Core.Scheduling;

public static class ScheduleWindowCalculator
{
    public static DateTimeOffset? GetCurrentWindowStart(
        QueueScheduleDefinition definition,
        DateTimeOffset instant,
        TimeZoneInfo timeZone)
    {
        ArgumentNullException.ThrowIfNull(definition);
        ArgumentNullException.ThrowIfNull(timeZone);

        DownloadSchedule schedule = new(definition.StartTime, definition.EndTime, definition.Days);
        if (!schedule.IsActiveAt(instant, timeZone))
        {
            return null;
        }

        DateTimeOffset local = TimeZoneInfo.ConvertTime(instant, timeZone);
        DateOnly startDate = DateOnly.FromDateTime(local.DateTime);
        if (schedule.CrossesMidnight && TimeOnly.FromDateTime(local.DateTime) <= definition.EndTime)
        {
            startDate = startDate.AddDays(-1);
        }

        return CreateInstant(startDate, definition.StartTime, timeZone);
    }

    public static DateTimeOffset? GetLatestMissedStart(
        QueueScheduleDefinition definition,
        DateTimeOffset afterExclusive,
        DateTimeOffset throughInclusive,
        TimeZoneInfo timeZone)
    {
        ArgumentNullException.ThrowIfNull(definition);
        ArgumentNullException.ThrowIfNull(timeZone);
        if (throughInclusive <= afterExclusive)
        {
            return null;
        }

        DateOnly firstDate = DateOnly.FromDateTime(TimeZoneInfo.ConvertTime(afterExclusive, timeZone).DateTime).AddDays(-1);
        DateOnly lastDate = DateOnly.FromDateTime(TimeZoneInfo.ConvertTime(throughInclusive, timeZone).DateTime);
        DateTimeOffset? latest = null;
        for (DateOnly date = firstDate; date <= lastDate; date = date.AddDays(1))
        {
            if (!Includes(definition.Days, date.DayOfWeek))
            {
                continue;
            }

            DateTimeOffset candidate = CreateInstant(date, definition.StartTime, timeZone);
            if (candidate > afterExclusive && candidate <= throughInclusive)
            {
                latest = candidate;
            }
        }

        return latest;
    }

    private static DateTimeOffset CreateInstant(DateOnly date, TimeOnly time, TimeZoneInfo timeZone)
    {
        DateTime local = date.ToDateTime(time, DateTimeKind.Unspecified);
        if (timeZone.IsInvalidTime(local))
        {
            local = local.AddHours(1);
        }

        TimeSpan offset = timeZone.GetUtcOffset(local);
        return new DateTimeOffset(local, offset).ToUniversalTime();
    }

    private static bool Includes(WeekDays days, DayOfWeek day)
        => day switch
        {
            DayOfWeek.Monday => days.HasFlag(WeekDays.Monday),
            DayOfWeek.Tuesday => days.HasFlag(WeekDays.Tuesday),
            DayOfWeek.Wednesday => days.HasFlag(WeekDays.Wednesday),
            DayOfWeek.Thursday => days.HasFlag(WeekDays.Thursday),
            DayOfWeek.Friday => days.HasFlag(WeekDays.Friday),
            DayOfWeek.Saturday => days.HasFlag(WeekDays.Saturday),
            DayOfWeek.Sunday => days.HasFlag(WeekDays.Sunday),
            _ => false
        };
}
