namespace XDM.Diagnostics;

public sealed class DiagnosticEventStore : IDiagnosticEventStore
{
    private const int MaximumEvents = 500;
    private readonly object _sync = new();
    private readonly List<DiagnosticEvent> _events = [];

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
            RedactContext(context));

        lock (_sync)
        {
            _events.Add(item);
            if (_events.Count > MaximumEvents)
            {
                _events.RemoveRange(0, _events.Count - MaximumEvents);
            }
        }

        Changed?.Invoke(this, EventArgs.Empty);
    }

    public void Clear()
    {
        lock (_sync)
        {
            _events.Clear();
        }

        Changed?.Invoke(this, EventArgs.Empty);
    }

    private static IReadOnlyDictionary<string, string?> RedactContext(
        IReadOnlyDictionary<string, string?>? context)
    {
        if (context is null || context.Count == 0)
        {
            return new Dictionary<string, string?>(StringComparer.Ordinal);
        }

        return context.ToDictionary(
            static pair => pair.Key,
            static pair => pair.Value is null ? null : SecretRedactor.Redact(pair.Value),
            StringComparer.Ordinal);
    }
}
