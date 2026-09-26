using XDM.Core.State;

namespace XDM.Platform;

/// <summary>
/// Transitional XGO-79 adapter that reports physical legacy executor facts to Go.
/// It carries observations only; it owns no admission, retry, queue, or UI policy.
/// </summary>
public sealed class DesktopGoExecutionObserver : IDisposable
{
    private readonly IApplicationState _state;
    private readonly DesktopGoProjectionClient _go;
    private readonly SemaphoreSlim _serial = new(1, 1);
    private bool _started;

    public DesktopGoExecutionObserver(IApplicationState state, DesktopGoProjectionClient go)
    {
        _state = state ?? throw new ArgumentNullException(nameof(state));
        _go = go ?? throw new ArgumentNullException(nameof(go));
    }

    public async Task InitializeAsync(CancellationToken cancellationToken = default)
    {
        if (_started)
        {
            return;
        }
        _started = true;
        _state.Changed += OnChanged;
        await _go.ObserveExecutionAsync(_state.Current, cancellationToken).ConfigureAwait(false);
    }

    private async void OnChanged(object? sender, ApplicationSnapshot snapshot)
    {
        await _serial.WaitAsync().ConfigureAwait(false);
        try
        {
            await _go.ObserveExecutionAsync(snapshot).ConfigureAwait(false);
        }
        catch (InvalidOperationException)
        {
            // Shutdown raced a host observation; the next host start restores authority.
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
            _state.Changed -= OnChanged;
        }
        _serial.Dispose();
        GC.SuppressFinalize(this);
    }
}
