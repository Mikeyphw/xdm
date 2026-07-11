using XDM.Core.Scheduling;

namespace XDM.Core.Queues;

public sealed class DownloadQueue
{
    private readonly List<string> _downloadIds = [];

    public DownloadQueue(string id, string name)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(id);
        ArgumentException.ThrowIfNullOrWhiteSpace(name);

        Id = id;
        Name = name;
    }

    public string Id { get; }

    public string Name { get; private set; }

    public IReadOnlyList<string> DownloadIds => _downloadIds;

    public DownloadSchedule? Schedule { get; private set; }

    public void Rename(string name)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(name);
        Name = name;
    }

    public bool Add(string downloadId)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(downloadId);

        if (_downloadIds.Contains(downloadId, StringComparer.Ordinal))
        {
            return false;
        }

        _downloadIds.Add(downloadId);
        return true;
    }

    public bool Remove(string downloadId)
        => _downloadIds.Remove(downloadId);

    public void SetSchedule(DownloadSchedule? schedule)
        => Schedule = schedule;
}
