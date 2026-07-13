namespace XDM.Core.Queues;

public sealed record QueueRuntimeSnapshot(
    IReadOnlySet<string> ActiveQueueIds,
    IReadOnlyDictionary<string, int> RunningCounts,
    DateTimeOffset UpdatedAt,
    IReadOnlySet<string>? RequestedQueueIds = null,
    IReadOnlyDictionary<string, string>? BlockedReasons = null)
{
    public static QueueRuntimeSnapshot Empty { get; } = new(
        new HashSet<string>(StringComparer.Ordinal),
        new Dictionary<string, int>(StringComparer.Ordinal),
        DateTimeOffset.UtcNow,
        new HashSet<string>(StringComparer.Ordinal),
        new Dictionary<string, string>(StringComparer.Ordinal));

    public bool IsActive(string queueId)
        => ActiveQueueIds.Contains(queueId);

    public bool IsRequested(string queueId)
        => RequestedQueueIds?.Contains(queueId) == true;

    public int GetRunningCount(string queueId)
        => RunningCounts.TryGetValue(queueId, out int count) ? count : 0;

    public string? GetBlockedReason(string queueId)
        => BlockedReasons?.GetValueOrDefault(queueId);
}
