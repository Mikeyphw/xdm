using XDM.Core.Queues;

namespace XDM.Core.Tests;

public sealed class DownloadQueueTests
{
    [Fact]
    public void Add_is_idempotent()
    {
        DownloadQueue queue = new("default", "Default");

        Assert.True(queue.Add("download-1"));
        Assert.False(queue.Add("download-1"));
        Assert.Single(queue.DownloadIds);
    }
}
