namespace XDM.Media;

internal sealed class FragmentResumeState
{
    private readonly string _formatDirectory;
    private readonly Dictionary<string, FragmentCheckpointEntry> _entries;

    private FragmentResumeState(
        string formatDirectory,
        Dictionary<string, FragmentCheckpointEntry> entries,
        double liveElapsedSeconds)
    {
        _formatDirectory = formatDirectory;
        _entries = entries;
        LiveElapsedSeconds = Math.Max(0, liveElapsedSeconds);
    }

    public double LiveElapsedSeconds { get; }

    public long DownloadedBytes => _entries.Values.Sum(static entry => entry.Length);

    public int Count => _entries.Count;

    public static FragmentResumeState FromCheckpoint(
        FragmentCheckpoint? checkpoint,
        string source,
        string formatId,
        string formatDirectory)
    {
        Dictionary<string, FragmentCheckpointEntry> entries = new(StringComparer.Ordinal);
        if (checkpoint is null
            || !string.Equals(checkpoint.Source, source, StringComparison.Ordinal)
            || !string.Equals(checkpoint.FormatId, formatId, StringComparison.Ordinal))
        {
            return new FragmentResumeState(formatDirectory, entries, 0);
        }

        IReadOnlyList<FragmentCheckpointEntry> checkpointEntries = checkpoint.Entries ?? [];
        if (checkpointEntries.Count == 0)
        {
            return new FragmentResumeState(formatDirectory, entries, 0);
        }

        foreach (FragmentCheckpointEntry entry in checkpointEntries)
        {
            if (!IsSafeRelativePath(entry.RelativePath) || entry.Length < 0)
            {
                continue;
            }

            string fullPath = Path.Combine(formatDirectory, entry.RelativePath);
            if (!File.Exists(fullPath))
            {
                continue;
            }

            long length = new FileInfo(fullPath).Length;
            if (length != entry.Length)
            {
                continue;
            }

            entries[entry.Id] = entry;
        }

        return new FragmentResumeState(formatDirectory, entries, checkpoint.LiveElapsedSeconds);
    }

    public bool TryReuse(FragmentPlanEntry plan)
    {
        ArgumentNullException.ThrowIfNull(plan);
        if (!_entries.TryGetValue(plan.Id, out FragmentCheckpointEntry? entry))
        {
            return false;
        }

        string fullPath = Path.Combine(_formatDirectory, entry.RelativePath);
        if (!File.Exists(fullPath) || new FileInfo(fullPath).Length != entry.Length)
        {
            _entries.Remove(plan.Id);
            return false;
        }

        if (!string.Equals(entry.Identity, plan.Identity, StringComparison.Ordinal))
        {
            _entries.Remove(plan.Id);
            return false;
        }

        return true;
    }

    public bool ContainsInitialization(string initializationIdentity)
        => _entries.Values.Any(entry =>
            entry.HasInitialization
            && string.Equals(entry.InitializationIdentity, initializationIdentity, StringComparison.Ordinal));

    public bool EntryContainsInitialization(string id, string initializationIdentity)
        => _entries.TryGetValue(id, out FragmentCheckpointEntry? entry)
            && entry.HasInitialization
            && string.Equals(entry.InitializationIdentity, initializationIdentity, StringComparison.Ordinal);

    public void Complete(FragmentPlanEntry plan, long length)
    {
        ArgumentNullException.ThrowIfNull(plan);
        if (length < 0)
        {
            throw new ArgumentOutOfRangeException(nameof(length), "Fragment length must be non-negative.");
        }

        _entries[plan.Id] = new FragmentCheckpointEntry(
            plan.Id,
            plan.Identity,
            plan.RelativePath,
            length,
            plan.Order,
            plan.HasInitialization,
            plan.InitializationIdentity);
    }

    public FragmentCheckpoint ToCheckpoint(
        string source,
        string formatId,
        string planId,
        double liveElapsedSeconds,
        DateTimeOffset updatedAtUtc)
    {
        FragmentCheckpointEntry[] entries = EntriesInOrder().ToArray();
        return new FragmentCheckpoint(
            source,
            formatId,
            entries.Select(static entry => entry.Id).ToArray(),
            entries.Sum(static entry => entry.Length),
            updatedAtUtc)
        {
            Version = FragmentCheckpoint.CurrentVersion,
            PlanId = planId,
            Entries = entries,
            LiveElapsedSeconds = Math.Max(0, liveElapsedSeconds)
        };
    }

    public IReadOnlyList<FragmentCheckpointEntry> EntriesInOrderSnapshot()
        => EntriesInOrder().ToArray();

    public IReadOnlyList<string> ExistingPathsInOrder()
        => EntriesInOrder()
            .Select(entry => Path.Combine(_formatDirectory, entry.RelativePath))
            .Where(File.Exists)
            .ToArray();

    private IEnumerable<FragmentCheckpointEntry> EntriesInOrder()
        => _entries.Values
            .OrderBy(static entry => entry.Order)
            .ThenBy(static entry => entry.Id, StringComparer.Ordinal);

    private static bool IsSafeRelativePath(string path)
    {
        if (string.IsNullOrWhiteSpace(path)
            || Path.IsPathRooted(path)
            || path.Contains("..", StringComparison.Ordinal)
            || path.IndexOfAny(Path.GetInvalidPathChars()) >= 0)
        {
            return false;
        }

        return true;
    }
}
