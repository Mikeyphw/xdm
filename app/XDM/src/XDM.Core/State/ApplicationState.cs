using XDM.Core.Downloads;

namespace XDM.Core.State;

public sealed class ApplicationState : IApplicationState
{
    private readonly object _sync = new();
    private ApplicationSnapshot _current = new(DateTimeOffset.UtcNow, true, []);

    public ApplicationSnapshot Current
    {
        get
        {
            lock (_sync)
            {
                return _current;
            }
        }
    }

    public event EventHandler<ApplicationSnapshot>? Changed;

    public void ReplaceDownloads(IEnumerable<DownloadSnapshot> downloads)
    {
        ArgumentNullException.ThrowIfNull(downloads);
        Publish(downloads.ToArray());
    }

    public void UpsertDownload(DownloadSnapshot download)
    {
        ArgumentNullException.ThrowIfNull(download);

        DownloadSnapshot[] next;
        lock (_sync)
        {
            List<DownloadSnapshot> downloads = [.. _current.Downloads];
            int index = downloads.FindIndex(item =>
                string.Equals(item.Id, download.Id, StringComparison.Ordinal));

            if (index >= 0)
            {
                downloads[index] = download;
            }
            else
            {
                downloads.Add(download);
            }

            next = [.. downloads.OrderByDescending(static item => item.UpdatedAt)];
            _current = _current with { Downloads = next };
        }

        Changed?.Invoke(this, _current);
    }

    public bool RemoveDownload(string downloadId)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(downloadId);

        ApplicationSnapshot next;
        lock (_sync)
        {
            DownloadSnapshot[] downloads = _current.Downloads
                .Where(item => !string.Equals(item.Id, downloadId, StringComparison.Ordinal))
                .ToArray();

            if (downloads.Length == _current.Downloads.Count)
            {
                return false;
            }

            next = _current with { Downloads = downloads };
            _current = next;
        }

        Changed?.Invoke(this, next);
        return true;
    }

    private void Publish(IReadOnlyList<DownloadSnapshot> downloads)
    {
        ApplicationSnapshot next;
        lock (_sync)
        {
            next = _current with { Downloads = downloads };
            _current = next;
        }

        Changed?.Invoke(this, next);
    }
}
