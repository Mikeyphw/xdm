using System.Text.Json;
using System.Text.Json.Serialization;
using XDM.BrowserIntegration;

namespace XDM.App.Services;

public sealed class BrowserCaptureAcknowledgementStore
{
    private const int MaximumRecords = 1024;
    private static readonly TimeSpan Retention = TimeSpan.FromDays(2);
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web)
    {
        WriteIndented = true,
        UnmappedMemberHandling = JsonUnmappedMemberHandling.Skip
    };

    private readonly object _sync = new();
    private readonly string _path;
    private Dictionary<string, BrowserCaptureAcknowledgementRecord>? _records;

    public BrowserCaptureAcknowledgementStore()
        : this(Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "xdm-modern",
            "browser-capture-acks.json"))
    {
    }

    public BrowserCaptureAcknowledgementStore(string path)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(path);
        _path = Path.GetFullPath(path);
    }

    public BrowserCaptureAcknowledgement? TryGet(string? requestId)
    {
        if (string.IsNullOrWhiteSpace(requestId))
        {
            return null;
        }

        lock (_sync)
        {
            EnsureLoaded();
            string key = requestId.Trim();
            if (_records!.TryGetValue(key, out BrowserCaptureAcknowledgementRecord? record)
                && record.CreatedAtUtc >= DateTimeOffset.UtcNow - Retention)
            {
                return new BrowserCaptureAcknowledgement(record.RequestId, record.Accepted, record.Reason, record.DownloadId);
            }

            return null;
        }
    }

    public void Save(BrowserCaptureAcknowledgement acknowledgement)
    {
        if (string.IsNullOrWhiteSpace(acknowledgement.RequestId))
        {
            return;
        }

        lock (_sync)
        {
            EnsureLoaded();
            _records![acknowledgement.RequestId.Trim()] = new BrowserCaptureAcknowledgementRecord(
                acknowledgement.RequestId.Trim(),
                acknowledgement.Accepted,
                string.IsNullOrWhiteSpace(acknowledgement.Reason) ? "unknown" : acknowledgement.Reason.Trim(),
                string.IsNullOrWhiteSpace(acknowledgement.DownloadId) ? null : acknowledgement.DownloadId.Trim(),
                DateTimeOffset.UtcNow);
            PruneLocked();
            PersistLocked();
        }
    }

    private void EnsureLoaded()
    {
        if (_records is not null)
        {
            return;
        }

        _records = new Dictionary<string, BrowserCaptureAcknowledgementRecord>(StringComparer.Ordinal);
        try
        {
            if (!File.Exists(_path))
            {
                return;
            }

            BrowserCaptureAcknowledgementEnvelope? envelope = JsonSerializer.Deserialize<BrowserCaptureAcknowledgementEnvelope>(
                File.ReadAllText(_path),
                JsonOptions);
            foreach (BrowserCaptureAcknowledgementRecord record in envelope?.Records ?? [])
            {
                if (!string.IsNullOrWhiteSpace(record.RequestId))
                {
                    _records[record.RequestId.Trim()] = record;
                }
            }

            PruneLocked();
        }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException or JsonException)
        {
            _records.Clear();
        }
    }

    private void PruneLocked()
    {
        DateTimeOffset threshold = DateTimeOffset.UtcNow - Retention;
        foreach (string key in _records!.Where(pair => pair.Value.CreatedAtUtc < threshold)
                     .Select(static pair => pair.Key)
                     .ToArray())
        {
            _records.Remove(key);
        }

        foreach (string key in _records.OrderByDescending(static pair => pair.Value.CreatedAtUtc)
                     .Skip(MaximumRecords)
                     .Select(static pair => pair.Key)
                     .ToArray())
        {
            _records.Remove(key);
        }
    }

    private void PersistLocked()
    {
        try
        {
            string? directory = Path.GetDirectoryName(_path);
            if (!string.IsNullOrEmpty(directory))
            {
                Directory.CreateDirectory(directory);
            }

            string temporary = _path + ".tmp";
            BrowserCaptureAcknowledgementEnvelope envelope = new(1, _records!.Values.OrderBy(static record => record.CreatedAtUtc).ToArray());
            File.WriteAllText(temporary, JsonSerializer.Serialize(envelope, JsonOptions));
            File.Move(temporary, _path, overwrite: true);
        }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException)
        {
            // Acknowledgement persistence is best-effort, but successful in-memory acknowledgement
            // for the current process remains authoritative.
        }
    }

    private sealed record BrowserCaptureAcknowledgementEnvelope(int Version, IReadOnlyList<BrowserCaptureAcknowledgementRecord> Records);

    private sealed record BrowserCaptureAcknowledgementRecord(
        string RequestId,
        bool Accepted,
        string Reason,
        string? DownloadId,
        DateTimeOffset CreatedAtUtc);
}
