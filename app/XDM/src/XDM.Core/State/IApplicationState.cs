using XDM.Core.Downloads;

namespace XDM.Core.State;

public interface IApplicationState
{
    ApplicationSnapshot Current { get; }

    event EventHandler<ApplicationSnapshot>? Changed;

    void SetCoreReady(bool ready);

    void ReplaceDownloads(IEnumerable<DownloadSnapshot> downloads);

    void UpsertDownload(DownloadSnapshot download);

    bool RemoveDownload(string downloadId);
}
