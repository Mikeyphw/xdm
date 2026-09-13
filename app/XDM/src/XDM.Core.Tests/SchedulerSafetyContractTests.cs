using XDM.Core.Scheduling;

namespace XDM.Core.Tests;

public sealed class SchedulerSafetyContractTests
{
    [Fact]
    public void NoWeekdaysDisablesScheduleInsteadOfRunningEveryDay()
    {
        QueueScheduleDefinition schedule = new(
            "schedule",
            "Schedule",
            true,
            "queue",
            new TimeOnly(8, 0),
            new TimeOnly(9, 0),
            WeekDays.None,
            MissedRunPolicy.Skip,
            ScheduleCompletionAction.None);

        QueueScheduleDefinition normalized = schedule.Normalize("queue");

        Assert.False(normalized.Enabled);
        Assert.Equal(WeekDays.None, normalized.Days);
    }

    [Fact]
    public void CompletionCountdownPersistsFullUiRange()
    {
        ScheduleCompletionAction action = new(ScheduleCompletionActionKind.Shutdown, 86_400);

        Assert.Equal(86_400, action.Normalize().CountdownSeconds);
    }

    [Fact]
    public void AntivirusTimeoutPersistsFullUiRange()
    {
        AntivirusScanSettings settings = new(true, "/scanner", [], 86_400);

        Assert.Equal(86_400, settings.Normalize().TimeoutSeconds);
    }
}
