using XDM.Core.Downloads;

namespace XDM.DownloadEngine;

public interface IDownloadRecoveryCoordinator
{
    event EventHandler? Changed;

    IReadOnlyList<DownloadRecoveryCandidate> Current { get; }

    Task ScanAsync(
        bool previousSessionWasUnclean,
        CancellationToken cancellationToken = default);

    Task<DownloadRecoveryCandidate> ValidateAsync(
        string candidateId,
        CancellationToken cancellationToken = default);

    void Dismiss(string candidateId);
}
