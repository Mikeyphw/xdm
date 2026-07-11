using XDM.Core.Settings;

namespace XDM.Persistence;

public sealed class SettingsService(ISettingsStore store) : ISettingsService, IDisposable
{
    private readonly SemaphoreSlim _gate = new(1, 1);

    public ApplicationSettings Current { get; private set; } = ApplicationSettings.CreateDefault();

    public event EventHandler<ApplicationSettings>? Changed;

    public async Task InitializeAsync(CancellationToken cancellationToken = default)
    {
        await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            Current = (await store.LoadAsync(cancellationToken).ConfigureAwait(false)
                ?? ApplicationSettings.CreateDefault()).Normalize();
        }
        finally
        {
            _gate.Release();
        }

        Changed?.Invoke(this, Current);
    }

    public async Task UpdateAsync(ApplicationSettings settings, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(settings);
        ApplicationSettings normalized = settings.Normalize();

        await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            await store.SaveAsync(normalized, cancellationToken).ConfigureAwait(false);
            Current = normalized;
        }
        finally
        {
            _gate.Release();
        }

        Changed?.Invoke(this, Current);
    }

    public void Dispose()
    {
        _gate.Dispose();
        GC.SuppressFinalize(this);
    }
}
