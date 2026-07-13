using XDM.Core.Downloads;
using XDM.Core.Queues;
using XDM.Core.Settings;
using XDM.DownloadEngine.Queues;

namespace XDM.DownloadEngine.Tests;

public sealed class QueueDependencyTests
{
    [Fact]
    public void BlocksUntilDependencyFinishes()
    {
        DownloadQueueDefinition[] queues =
        [
            new DownloadQueueDefinition("prepare", "Prepare", 1, 0),
            new DownloadQueueDefinition("install", "Install", 1, 0, ["prepare"], true)
        ];
        DownloadSnapshot pending = CreateDownload("prepare", DownloadState.Downloading);

        QueueDependencyEvaluation result = QueueDependencyEvaluator.Evaluate("install", queues, [pending]);

        Assert.False(result.CanStart);
        Assert.Contains("Prepare", result.BlockedReason, StringComparison.Ordinal);
    }

    [Fact]
    public void SuccessfulDependencyAllowsQueue()
    {
        DownloadQueueDefinition[] queues =
        [
            new DownloadQueueDefinition("prepare", "Prepare", 1, 0),
            new DownloadQueueDefinition("install", "Install", 1, 0, ["prepare"], true)
        ];
        DownloadSnapshot completed = CreateDownload("prepare", DownloadState.Completed);

        QueueDependencyEvaluation result = QueueDependencyEvaluator.Evaluate("install", queues, [completed]);

        Assert.True(result.CanStart);
        Assert.Null(result.BlockedReason);
    }

    [Fact]
    public void FailedDependencyBlocksSuccessfulOnlyQueue()
    {
        DownloadQueueDefinition[] queues =
        [
            new DownloadQueueDefinition("prepare", "Prepare", 1, 0),
            new DownloadQueueDefinition("install", "Install", 1, 0, ["prepare"], true)
        ];
        DownloadSnapshot failed = CreateDownload("prepare", DownloadState.Failed);

        QueueDependencyEvaluation result = QueueDependencyEvaluator.Evaluate("install", queues, [failed]);

        Assert.False(result.CanStart);
        Assert.Contains("successfully", result.BlockedReason, StringComparison.Ordinal);
    }

    [Fact]
    public void CompletionOnlyDependencyAllowsFailedTerminalQueue()
    {
        DownloadQueueDefinition[] queues =
        [
            new DownloadQueueDefinition("prepare", "Prepare", 1, 0),
            new DownloadQueueDefinition("install", "Install", 1, 0, ["prepare"], false)
        ];

        QueueDependencyEvaluation result = QueueDependencyEvaluator.Evaluate(
            "install",
            queues,
            [CreateDownload("prepare", DownloadState.Failed)]);

        Assert.True(result.CanStart);
    }

    [Fact]
    public void NormalizationRemovesCircularDependencyEdges()
    {
        DownloadQueueDefinition[] normalized = DownloadQueueDependencyGraph.Normalize(
        [
            new DownloadQueueDefinition("a", "A", 1, 0, ["b"]),
            new DownloadQueueDefinition("b", "B", 1, 0, ["a"])
        ]);

        Assert.All(normalized, static queue => Assert.Empty(queue.DependsOnQueueIds ?? []));
    }

    [Fact]
    public void StartOrderRequestsPrerequisitesBeforeDependentQueue()
    {
        DownloadQueueDefinition[] queues =
        [
            new DownloadQueueDefinition("fetch", "Fetch", 1, 0),
            new DownloadQueueDefinition("verify", "Verify", 1, 0, ["fetch"]),
            new DownloadQueueDefinition("install", "Install", 1, 0, ["verify"])
        ];

        string[] order = DownloadQueueDependencyGraph.GetStartOrder("install", queues);

        Assert.Equal(new[] { "fetch", "verify", "install" }, order);
    }

    private static DownloadSnapshot CreateDownload(string queueId, DownloadState state)
        => new(
            Guid.NewGuid().ToString("N"),
            "file.bin",
            new Uri("https://example.test/file.bin"),
            Path.Combine(Path.GetTempPath(), "file.bin"),
            0,
            100,
            0,
            state,
            DateTimeOffset.UtcNow,
            QueueId: queueId);
}
