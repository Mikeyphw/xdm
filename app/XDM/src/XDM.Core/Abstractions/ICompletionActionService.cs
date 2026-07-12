using XDM.Core.Scheduling;

namespace XDM.Core.Abstractions;

public interface ICompletionActionService
{
    IReadOnlyList<CompletionActionCapability> GetCapabilities();

    Task<CompletionActionResult> ExecuteAsync(
        ScheduleCompletionAction action,
        CancellationToken cancellationToken = default);
}
