using XDM.Core.Product;

namespace XDM.Core.Abstractions;

public interface IUpdateService
{
    Task<UpdateCheckResult> CheckAsync(CancellationToken cancellationToken = default);

    Task<UpdateCheckResult> CheckAsync(
        UpdateChannel channel,
        CancellationToken cancellationToken = default);

    Task<StagedUpdateResult> StageAsync(
        UpdateCheckResult update,
        IProgress<double>? progress = null,
        CancellationToken cancellationToken = default);

    Task LaunchStagedUpdateAsync(
        StagedUpdateResult stagedUpdate,
        int currentProcessId,
        CancellationToken cancellationToken = default);

    Task MarkCurrentVersionHealthyAsync(CancellationToken cancellationToken = default);
}
