using XDM.Core.Downloads;
using XDM.Core.Queues;
using XDM.Core.Settings;

namespace XDM.DownloadEngine.Queues;

public sealed record QueueDependencyEvaluation(bool CanStart, string? BlockedReason)
{
    public static QueueDependencyEvaluation Allowed { get; } = new(true, null);
}

public static class QueueDependencyEvaluator
{
    public static QueueDependencyEvaluation Evaluate(
        string queueId,
        IReadOnlyList<DownloadQueueDefinition> queues,
        IReadOnlyList<DownloadSnapshot> downloads)
    {
        DownloadQueueDefinition? queue = queues.FirstOrDefault(item =>
            string.Equals(item.Id, queueId, StringComparison.Ordinal));
        if (queue is null)
        {
            return QueueDependencyEvaluation.Allowed;
        }

        foreach (string dependencyId in queue.DependsOnQueueIds ?? [])
        {
            DownloadQueueDefinition? dependency = queues.FirstOrDefault(item =>
                string.Equals(item.Id, dependencyId, StringComparison.Ordinal));
            if (dependency is null)
            {
                return new QueueDependencyEvaluation(false, $"Dependency '{dependencyId}' is missing.");
            }

            DownloadSnapshot[] dependencyDownloads = downloads
                .Where(download => string.Equals(download.QueueId, dependencyId, StringComparison.Ordinal))
                .ToArray();
            bool hasPending = dependencyDownloads.Any(static download =>
                download.State is not (DownloadState.Completed or DownloadState.Failed or DownloadState.Cancelled));
            if (hasPending)
            {
                return new QueueDependencyEvaluation(false, $"Waiting for queue '{dependency.Name}' to finish.");
            }

            if (queue.RequireSuccessfulDependencies
                && dependencyDownloads.Any(static download => download.State is DownloadState.Failed or DownloadState.Cancelled))
            {
                return new QueueDependencyEvaluation(false, $"Queue '{dependency.Name}' did not complete successfully.");
            }
        }

        return QueueDependencyEvaluation.Allowed;
    }
}
