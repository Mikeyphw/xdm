namespace XDM.Diagnostics;

public interface IDeterministicDownloadTestService
{
    DeterministicDownloadTestResult? LastResult { get; }

    event EventHandler? Changed;

    Task<DeterministicDownloadTestResult> RunAsync(CancellationToken cancellationToken = default);
}
