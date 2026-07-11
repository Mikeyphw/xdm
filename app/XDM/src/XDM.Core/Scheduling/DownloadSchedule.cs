namespace XDM.Core.Scheduling;

public readonly record struct DownloadSchedule(TimeOnly StartTime, TimeOnly EndTime, WeekDays Days)
{
    public bool CrossesMidnight => EndTime < StartTime;

    public bool Includes(DayOfWeek day)
        => (Days & ToFlag(day)) != 0;

    public bool IsActiveAt(DateTimeOffset instant, TimeZoneInfo timeZone)
    {
        ArgumentNullException.ThrowIfNull(timeZone);

        DateTimeOffset local = TimeZoneInfo.ConvertTime(instant, timeZone);
        TimeOnly time = TimeOnly.FromDateTime(local.DateTime);

        if (!CrossesMidnight)
        {
            return Includes(local.DayOfWeek) && time >= StartTime && time <= EndTime;
        }

        if (time >= StartTime)
        {
            return Includes(local.DayOfWeek);
        }

        DayOfWeek previousDay = local.AddDays(-1).DayOfWeek;
        return time <= EndTime && Includes(previousDay);
    }

    private static WeekDays ToFlag(DayOfWeek day)
        => day switch
        {
            DayOfWeek.Monday => WeekDays.Monday,
            DayOfWeek.Tuesday => WeekDays.Tuesday,
            DayOfWeek.Wednesday => WeekDays.Wednesday,
            DayOfWeek.Thursday => WeekDays.Thursday,
            DayOfWeek.Friday => WeekDays.Friday,
            DayOfWeek.Saturday => WeekDays.Saturday,
            DayOfWeek.Sunday => WeekDays.Sunday,
            _ => WeekDays.None
        };
}
