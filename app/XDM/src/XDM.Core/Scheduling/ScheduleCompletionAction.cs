namespace XDM.Core.Scheduling;

public sealed record ScheduleCompletionAction(
    ScheduleCompletionActionKind Kind,
    int CountdownSeconds = 30,
    string? ExecutablePath = null,
    IReadOnlyList<string>? Arguments = null)
{
    public static ScheduleCompletionAction None { get; } = new(ScheduleCompletionActionKind.None, 0);

    public ScheduleCompletionAction Normalize()
        => this with
        {
            CountdownSeconds = Kind == ScheduleCompletionActionKind.None
                ? 0
                : Math.Clamp(CountdownSeconds, 0, 300),
            ExecutablePath = string.IsNullOrWhiteSpace(ExecutablePath) ? null : ExecutablePath.Trim(),
            Arguments = Arguments?
                .Where(static argument => !string.IsNullOrWhiteSpace(argument))
                .Select(static argument => argument.Trim())
                .Take(64)
                .ToArray() ?? []
        };
}
