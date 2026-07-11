using XDM.Core.Scheduling;

namespace XDM.Core.Tests;

public sealed class DownloadScheduleTests
{
    [Fact]
    public void Overnight_schedule_carries_into_the_next_day()
    {
        DownloadSchedule schedule = new(
            new TimeOnly(22, 0),
            new TimeOnly(2, 0),
            WeekDays.Monday);

        DateTimeOffset tuesdayAtOneUtc = new(2026, 7, 14, 1, 0, 0, TimeSpan.Zero);

        Assert.True(schedule.IsActiveAt(tuesdayAtOneUtc, TimeZoneInfo.Utc));
    }
}
