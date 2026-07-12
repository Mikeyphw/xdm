namespace XDM.Core.Scheduling;

public sealed record CompletionActionCapability(
    ScheduleCompletionActionKind Kind,
    bool IsSupported,
    string Message);
