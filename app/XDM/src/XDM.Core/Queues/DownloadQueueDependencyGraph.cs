using XDM.Core.Settings;

namespace XDM.Core.Queues;

public static class DownloadQueueDependencyGraph
{
    public static DownloadQueueDefinition[] Normalize(IReadOnlyList<DownloadQueueDefinition> queues)
    {
        ArgumentNullException.ThrowIfNull(queues);
        Dictionary<string, string[]> graph = queues.ToDictionary(
            static queue => queue.Id,
            queue => (queue.DependsOnQueueIds ?? [])
                .Where(dependency => !string.Equals(dependency, queue.Id, StringComparison.Ordinal))
                .Where(dependency => queues.Any(candidate => string.Equals(candidate.Id, dependency, StringComparison.Ordinal)))
                .Distinct(StringComparer.Ordinal)
                .ToArray(),
            StringComparer.Ordinal);

        List<DownloadQueueDefinition> normalized = new(queues.Count);
        foreach (DownloadQueueDefinition queue in queues)
        {
            string[] safeDependencies = graph[queue.Id]
                .Where(dependency => !HasPath(dependency, queue.Id, graph, new HashSet<string>(StringComparer.Ordinal)))
                .ToArray();
            normalized.Add(queue with { DependsOnQueueIds = safeDependencies });
        }

        return normalized.ToArray();
    }

    public static string[] GetStartOrder(
        string queueId,
        IReadOnlyList<DownloadQueueDefinition> queues)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(queueId);
        ArgumentNullException.ThrowIfNull(queues);
        Dictionary<string, DownloadQueueDefinition> byId = queues.ToDictionary(
            static queue => queue.Id,
            StringComparer.Ordinal);
        List<string> order = [];
        HashSet<string> visited = new(StringComparer.Ordinal);
        Visit(queueId, byId, visited, order);
        return order.ToArray();
    }

    private static void Visit(
        string queueId,
        Dictionary<string, DownloadQueueDefinition> queues,
        HashSet<string> visited,
        List<string> order)
    {
        if (!visited.Add(queueId))
        {
            return;
        }

        if (queues.TryGetValue(queueId, out DownloadQueueDefinition? queue))
        {
            foreach (string dependencyId in queue.DependsOnQueueIds ?? [])
            {
                Visit(dependencyId, queues, visited, order);
            }
        }

        order.Add(queueId);
    }

    private static bool HasPath(
        string current,
        string target,
        Dictionary<string, string[]> graph,
        HashSet<string> visited)
    {
        if (string.Equals(current, target, StringComparison.Ordinal))
        {
            return true;
        }

        if (!visited.Add(current) || !graph.TryGetValue(current, out string[]? dependencies))
        {
            return false;
        }

        return dependencies.Any(dependency => HasPath(dependency, target, graph, visited));
    }
}
