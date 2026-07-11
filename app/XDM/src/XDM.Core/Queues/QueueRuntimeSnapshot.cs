namespace XDM.Core.Queues;

public sealed record QueueRuntimeSnapshot(
    IReadOnlySet<string> ActiveQueueIds,
    IReadOnlyDictionary<string, int> RunningCounts,
    DateTimeOffset UpdatedAt)
{
    public static QueueRuntimeSnapshot Empty { get; } = new(
        new HashSet<string>(StringComparer.Ordinal),
        new Dictionary<string, int>(StringComparer.Ordinal),
        DateTimeOffset.UtcNow);

    public bool IsActive(string queueId)
        => ActiveQueueIds.Contains(queueId);

    public int GetRunningCount(string queueId)
        => RunningCounts.TryGetValue(queueId, out int count) ? count : 0;
}
