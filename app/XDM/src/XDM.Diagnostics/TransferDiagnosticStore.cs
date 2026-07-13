using XDM.Core.Diagnostics;

namespace XDM.Diagnostics;

public sealed class TransferDiagnosticStore : ITransferDiagnosticSink, ITransferDiagnosticSource
{
    private const int MaximumEvents = 2000;
    private readonly object _sync = new();
    private readonly List<TransferDiagnosticEvent> _events = [];

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
        }

        Changed?.Invoke(this, EventArgs.Empty);
    }

    private static Dictionary<string, string?> RedactContext(
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
