using XDM.Core.Scheduling;

namespace XDM.Core.Tests;

public sealed class ScheduleWindowCalculatorTests
{
    private static readonly QueueScheduleDefinition EveryDaySchedule = new(
        "night",
        "Night",
        true,
        "default",
        new TimeOnly(22, 0),
        new TimeOnly(2, 0),
        WeekDays.EveryDay,
        MissedRunPolicy.RunImmediately,
        ScheduleCompletionAction.None);

    [Fact]
    public void ResolvesOvernightWindowToPreviousLocalDay()
    {
        DateTimeOffset instant = new(2026, 7, 14, 1, 0, 0, TimeSpan.Zero);

        DateTimeOffset? start = ScheduleWindowCalculator.GetCurrentWindowStart(
            EveryDaySchedule,
            instant,
            TimeZoneInfo.Utc);

        Assert.Equal(new DateTimeOffset(2026, 7, 13, 22, 0, 0, TimeSpan.Zero), start);
    }

    [Fact]
    public void FindsLatestMissedStartBetweenEvaluations()
    {
        DateTimeOffset after = new(2026, 7, 13, 21, 0, 0, TimeSpan.Zero);
        DateTimeOffset through = new(2026, 7, 14, 5, 0, 0, TimeSpan.Zero);

        DateTimeOffset? missed = ScheduleWindowCalculator.GetLatestMissedStart(
            EveryDaySchedule,
            after,
            through,
            TimeZoneInfo.Utc);

        Assert.Equal(new DateTimeOffset(2026, 7, 13, 22, 0, 0, TimeSpan.Zero), missed);
    }
}
