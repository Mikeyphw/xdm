namespace XDM.Platform;

/// <summary>
/// Reports physical desktop-host transfer facts back to Go. The broker owns only I/O mechanism;
/// Go remains the durable download/projection authority.
/// </summary>
public sealed class DesktopGoExecutionObserver : IDisposable
{
    private readonly DesktopGoDownloadExecutionBroker _broker;
    private readonly DesktopGoProjectionClient _go;
    private readonly SemaphoreSlim _serial = new(1, 1);
    private bool _started;

    public DesktopGoExecutionObserver(DesktopGoDownloadExecutionBroker broker, DesktopGoProjectionClient go)
    {
        _broker = broker ?? throw new ArgumentNullException(nameof(broker));
        _go = go ?? throw new ArgumentNullException(nameof(go));
    }

    public Task InitializeAsync(CancellationToken cancellationToken = default)
    {
        cancellationToken.ThrowIfCancellationRequested();
        if (_started)
        {
            return Task.CompletedTask;
        }
        _started = true;
        _broker.SnapshotChanged += OnSnapshotChanged;
        return Task.CompletedTask;
    }

    private async void OnSnapshotChanged(object? sender, DesktopGoExecutionSnapshotEventArgs eventArgs)
    {
        await _serial.WaitAsync().ConfigureAwait(false);
        try
        {
            HashSet<string> activeIds = eventArgs.Downloads.Select(static item => item.Id).ToHashSet(StringComparer.Ordinal);
            DesktopGoDownloadProjection[] merged = _go.CurrentDownloads
                .Where(item => !activeIds.Contains(item.Id))
                .Concat(eventArgs.Downloads)
                .ToArray();
            await _go.ObserveExecutionAsync(merged).ConfigureAwait(false);
        }
        catch (InvalidOperationException)
        {
            // Shutdown raced a host observation; durable Go state remains authoritative.
        }
        finally
        {
            _serial.Release();
        }
    }

    public void Dispose()
    {
        if (_started)
        {
            _broker.SnapshotChanged -= OnSnapshotChanged;
        }
        _serial.Dispose();
        GC.SuppressFinalize(this);
    }
}
