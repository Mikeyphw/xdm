using System.Text.Json;

namespace XDM.Diagnostics;

public sealed class DiagnosticEventStore : IDiagnosticEventStore
{
    private const int MaximumEvents = 500;
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        WriteIndented = true
    };

    private readonly object _sync = new();
    private readonly List<DiagnosticEvent> _events = [];
    private readonly string? _persistencePath;

    public DiagnosticEventStore()
    {
    }

    internal DiagnosticEventStore(string persistencePath)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(persistencePath);
        _persistencePath = persistencePath;
        LoadPersisted();
    }

    public static DiagnosticEventStore Persistent()
        => new(DiagnosticRingPaths.EventRingPath());

    public event EventHandler? Changed;

    public IReadOnlyList<DiagnosticEvent> Snapshot()
    {
        lock (_sync)
        {
            return _events.ToArray();
        }
    }

    public void Record(
        DiagnosticSeverity severity,
        string code,
        string message,
        IReadOnlyDictionary<string, string?>? context = null)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(code);
        ArgumentException.ThrowIfNullOrWhiteSpace(message);

        DiagnosticEvent item = new(
            DateTimeOffset.UtcNow,
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

    public void Clear()
    {
        lock (_sync)
        {
            _events.Clear();
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
            DiagnosticEvent[]? items = JsonSerializer.Deserialize<DiagnosticEvent[]>(
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
            // Diagnostics must not make startup fail. A later support bundle still contains the recovery marker.
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
            // Do not recursively log from the diagnostic store itself.
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
