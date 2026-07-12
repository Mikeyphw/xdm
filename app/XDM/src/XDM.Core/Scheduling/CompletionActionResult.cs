namespace XDM.Core.Scheduling;

public sealed record CompletionActionResult(
    ScheduleCompletionActionKind Kind,
    bool Succeeded,
    string Message);
