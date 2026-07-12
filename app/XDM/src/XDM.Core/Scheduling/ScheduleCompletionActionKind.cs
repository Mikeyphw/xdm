namespace XDM.Core.Scheduling;

public enum ScheduleCompletionActionKind
{
    None,
    ExitApplication,
    Shutdown,
    Sleep,
    Hibernate,
    LogOut,
    RunCommand
}
