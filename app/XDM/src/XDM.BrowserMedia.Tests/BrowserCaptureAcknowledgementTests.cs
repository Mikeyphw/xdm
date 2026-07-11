using XDM.BrowserIntegration;

namespace XDM.BrowserMedia.Tests;

public sealed class BrowserCaptureAcknowledgementTests
{
    [Fact]
    public async Task CompletesOnlyAfterAcceptedByDownloadManagerBoundary()
    {
        BrowserCaptureEventArgs eventArgs = new(new BrowserCaptureRequest(new Uri("https://example.test/file.zip")));
        Task<BrowserCaptureDecision> pending = eventArgs.WaitForDecisionAsync(TimeSpan.FromSeconds(1), CancellationToken.None);

        Assert.False(pending.IsCompleted);
        Assert.True(eventArgs.Accept("download-1"));
        BrowserCaptureDecision decision = await pending;

        Assert.True(decision.Accepted);
        Assert.Equal("download-1", decision.DownloadId);
        Assert.False(eventArgs.Reject("too_late"));
    }

    [Fact]
    public async Task PropagatesExplicitRejection()
    {
        BrowserCaptureEventArgs eventArgs = new(new BrowserCaptureRequest(new Uri("https://example.test/file.zip")));
        Assert.True(eventArgs.Reject("queue_rejected"));

        BrowserCaptureDecision decision = await eventArgs.WaitForDecisionAsync(
            TimeSpan.FromSeconds(1),
            CancellationToken.None);

        Assert.False(decision.Accepted);
        Assert.Equal("queue_rejected", decision.Reason);
    }
}
