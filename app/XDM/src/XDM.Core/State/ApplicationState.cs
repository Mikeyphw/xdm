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
            DownloadSnapshot[] current = _current.Downloads as DownloadSnapshot[]
                ?? _current.Downloads.ToArray();
            DownloadSnapshot[] updated;
            if (_downloadIndexes.TryGetValue(download.Id, out int existingIndex))
            {
                updated = new DownloadSnapshot[current.Length];
                if (existingIndex > 0)
                {
                    Array.Copy(current, 0, updated, 1, existingIndex);
                    for (int index = 0; index < existingIndex; index++)
                    {
                        _downloadIndexes[current[index].Id] = index + 1;
                    }
                }

                int tailLength = current.Length - existingIndex - 1;
                if (tailLength > 0)
                {
                    Array.Copy(current, existingIndex + 1, updated, existingIndex + 1, tailLength);
                }

                updated[0] = download;
                _downloadIndexes[download.Id] = 0;
            }
            else
            {
                updated = new DownloadSnapshot[current.Length + 1];
                Array.Copy(current, 0, updated, 1, current.Length);
                updated[0] = download;
                _downloadIndexes[download.Id] = 0;
                for (int index = 0; index < current.Length; index++)
                {
                    _downloadIndexes[current[index].Id] = index + 1;
                }
            }

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
