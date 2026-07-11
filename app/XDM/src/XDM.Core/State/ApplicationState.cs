using XDM.Core.Downloads;

namespace XDM.Core.State;

public sealed class ApplicationState : IApplicationState
{
    private readonly object _sync = new();
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

        ApplicationSnapshot next;
        lock (_sync)
        {
            next = _current with { Downloads = downloads.ToArray() };
            _current = next;
        }

        Changed?.Invoke(this, next);
    }
}
