using System.Text.Json;
using XDM.Core.Diagnostics;

namespace XDM.Diagnostics;

public sealed class TransferDiagnosticStore : ITransferDiagnosticSink, ITransferDiagnosticSource
{
    private const int MaximumEvents = 2000;
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        WriteIndented = true
    };

    private readonly object _sync = new();
    private readonly List<TransferDiagnosticEvent> _events = [];
    private readonly string? _persistencePath;

    public TransferDiagnosticStore()
    {
    }

    internal TransferDiagnosticStore(string persistencePath)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(persistencePath);
        _persistencePath = persistencePath;
        LoadPersisted();
    }

    public static TransferDiagnosticStore Persistent()
        => new(DiagnosticRingPaths.TransferRingPath());

    public event EventHandler? Changed;

    public IReadOnlyList<TransferDiagnosticEvent> Snapshot(string? downloadId = null)
    {
        lock (_sync)
        {
            IEnumerable<TransferDiagnosticEvent> query = _events;
            if (!string.IsNullOrWhiteSpace(downloadId))
            {
                query = query.Where(item => string.Equals(item.DownloadId, downloadId, StringComparison.Ordinal));
            }

            return query.ToArray();
        }
    }

    public void Record(
        string downloadId,
        TransferDiagnosticStage stage,
        TransferDiagnosticSeverity severity,
        string code,
        string message,
        IReadOnlyDictionary<string, string?>? context = null)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(downloadId);
        ArgumentException.ThrowIfNullOrWhiteSpace(code);
        ArgumentException.ThrowIfNullOrWhiteSpace(message);

        TransferDiagnosticEvent item = new(
            DateTimeOffset.UtcNow,
            downloadId.Trim(),
            stage,
            severity,
            code.Trim(),
            SecretRedactor.Redact(message),
            SecretRedactor.RedactContext(context));

        lock (_sync)
        {
            _events.Add(item);
            TrimLocked();
            PersistLocked();
        }

        Changed?.Invoke(this, EventArgs.Empty);
    }

    public void Clear(string? downloadId = null)
    {
        lock (_sync)
        {
            if (string.IsNullOrWhiteSpace(downloadId))
            {
                _events.Clear();
            }
            else
            {
                _events.RemoveAll(item => string.Equals(item.DownloadId, downloadId, StringComparison.Ordinal));
            }

            PersistLocked();
        }

        Changed?.Invoke(this, EventArgs.Empty);
    }

    private void LoadPersisted()
    {
        if (_persistencePath is null || !File.Exists(_persistencePath))
        {
            return;
        }

        try
        {
            TransferDiagnosticEvent[]? items = JsonSerializer.Deserialize<TransferDiagnosticEvent[]>(
                File.ReadAllText(_persistencePath),
                JsonOptions);
            if (items is null)
            {
                return;
            }

            _events.AddRange(items.Select(static item => item with
            {
                Message = SecretRedactor.Redact(item.Message),
                Context = SecretRedactor.RedactContext(item.Context)
            }));
            TrimLocked();
        }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException or JsonException)
        {
        }
    }

    private void PersistLocked()
    {
        if (_persistencePath is null)
        {
            return;
        }

        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(_persistencePath)!);
            string tempPath = _persistencePath + $".{Guid.NewGuid():N}.tmp";
            File.WriteAllText(tempPath, JsonSerializer.Serialize(_events, JsonOptions));
            File.Move(tempPath, _persistencePath, overwrite: true);
        }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException or JsonException)
        {
        }
    }

    private void TrimLocked()
    {
        if (_events.Count > MaximumEvents)
        {
            _events.RemoveRange(0, _events.Count - MaximumEvents);
        }
    }
}
