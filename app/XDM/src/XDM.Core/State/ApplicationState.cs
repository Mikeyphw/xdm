using XDM.Core.Downloads;

namespace XDM.Core.State;

public sealed class ApplicationState : IApplicationState
{
    private readonly object _sync = new();
    private readonly Dictionary<string, int> _downloadIndexes = new(StringComparer.Ordinal);
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
        DownloadSnapshot[] ordered = downloads
            .OrderByDescending(static item => item.UpdatedAt)
            .ToArray();

        ApplicationSnapshot next;
        lock (_sync)
        {
            RebuildIndexes(ordered);
            next = _current with { Downloads = ordered };
            _current = next;
        }

        Changed?.Invoke(this, next);
    }

    public void UpsertDownload(DownloadSnapshot download)
    {
        ArgumentNullException.ThrowIfNull(download);

        ApplicationSnapshot next;
        lock (_sync)
        {
            IReadOnlyList<DownloadSnapshot> currentDownloads = _current.Downloads;
            DownloadSnapshot[] updated;
            if (_downloadIndexes.TryGetValue(download.Id, out int existingIndex))
            {
                updated = new DownloadSnapshot[currentDownloads.Count];
                updated[0] = download;
                if (existingIndex > 0)
                {
                    for (int index = 0; index < existingIndex; index++)
                    {
                        updated[index + 1] = currentDownloads[index];
                    }
                }

                for (int index = existingIndex + 1; index < currentDownloads.Count; index++)
                {
                    updated[index] = currentDownloads[index];
                }
            }
            else
            {
                updated = new DownloadSnapshot[currentDownloads.Count + 1];
                updated[0] = download;
                for (int index = 0; index < currentDownloads.Count; index++)
                {
                    updated[index + 1] = currentDownloads[index];
                }
            }

            RebuildIndexes(updated);
            next = _current with { Downloads = updated };
            _current = next;
        }

        Changed?.Invoke(this, next);
    }

    public bool RemoveDownload(string downloadId)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(downloadId);

        ApplicationSnapshot next;
        lock (_sync)
        {
            if (!_downloadIndexes.TryGetValue(downloadId, out int removeIndex))
            {
                return false;
            }

            IReadOnlyList<DownloadSnapshot> currentDownloads = _current.Downloads;
            DownloadSnapshot[] downloads = new DownloadSnapshot[currentDownloads.Count - 1];
            int destinationIndex = 0;
            for (int sourceIndex = 0; sourceIndex < currentDownloads.Count; sourceIndex++)
            {
                if (sourceIndex == removeIndex)
                {
                    continue;
                }

                downloads[destinationIndex] = currentDownloads[sourceIndex];
                destinationIndex++;
            }

            RebuildIndexes(downloads);
            next = _current with { Downloads = downloads };
            _current = next;
        }

        Changed?.Invoke(this, next);
        return true;
    }

    private void RebuildIndexes(DownloadSnapshot[] downloads)
    {
        _downloadIndexes.Clear();
        for (int index = 0; index < downloads.Length; index++)
        {
            _downloadIndexes[downloads[index].Id] = index;
        }
    }
}
