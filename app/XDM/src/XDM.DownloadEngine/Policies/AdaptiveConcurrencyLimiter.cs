namespace XDM.DownloadEngine.Policies;

internal sealed class AdaptiveConcurrencyLimiter
{
    private readonly object _sync = new();
    private readonly Dictionary<string, Entry> _entries = new(StringComparer.OrdinalIgnoreCase);

    public ValueTask<IDisposable> AcquireAsync(
        string key,
        int limit,
        CancellationToken cancellationToken)
        => AcquireAsync(key, () => limit, cancellationToken);

    public async ValueTask<IDisposable> AcquireAsync(
        string key,
        Func<int> limitProvider,
        CancellationToken cancellationToken)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(key);
        ArgumentNullException.ThrowIfNull(limitProvider);
        while (true)
        {
            Task waitTask;
            lock (_sync)
            {
                Entry entry = GetEntry(key);
                int normalizedLimit = Math.Max(1, limitProvider());
                if (entry.Active < normalizedLimit)
                {
                    entry.Active++;
                    return new Lease(this, key);
                }

                waitTask = entry.Changed.Task;
            }

            await waitTask.WaitAsync(cancellationToken).ConfigureAwait(false);
        }
    }

    public void NotifyLimitsChanged()
    {
        TaskCompletionSource<bool>[] waiters;
        lock (_sync)
        {
            waiters = _entries.Values
                .Select(static entry => entry.ReplaceSignal())
                .ToArray();
        }

        foreach (TaskCompletionSource<bool> waiter in waiters)
        {
            waiter.TrySetResult(true);
        }
    }

    private Entry GetEntry(string key)
    {
        if (_entries.TryGetValue(key, out Entry? entry))
        {
            return entry;
        }

        entry = new Entry();
        _entries.Add(key, entry);
        return entry;
    }

    private void Release(string key)
    {
        TaskCompletionSource<bool> changed;
        lock (_sync)
        {
            Entry entry = GetEntry(key);
            if (entry.Active > 0)
            {
                entry.Active--;
            }

            changed = entry.ReplaceSignal();
            if (entry.Active == 0)
            {
                _entries.Remove(key);
            }
        }

        changed.TrySetResult(true);
    }

    private sealed class Entry
    {
        public int Active { get; set; }

        public TaskCompletionSource<bool> Changed { get; private set; } = CreateSignal();

        public TaskCompletionSource<bool> ReplaceSignal()
        {
            TaskCompletionSource<bool> previous = Changed;
            Changed = CreateSignal();
            return previous;
        }

        private static TaskCompletionSource<bool> CreateSignal()
            => new(TaskCreationOptions.RunContinuationsAsynchronously);
    }

    private sealed class Lease(AdaptiveConcurrencyLimiter owner, string key) : IDisposable
    {
        private AdaptiveConcurrencyLimiter? _owner = owner;

        public void Dispose()
        {
            AdaptiveConcurrencyLimiter? current = Interlocked.Exchange(ref _owner, null);
            current?.Release(key);
        }
    }
}
