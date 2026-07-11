namespace XDM.DownloadEngine.Tests;

public sealed class DownloadRetryPolicyTests
{
    [Fact]
    public void UsesExponentialBackoffWithoutJitter()
    {
        DownloadRetryPolicy policy = new(4, TimeSpan.FromMilliseconds(100), 0);

        Assert.Equal(TimeSpan.FromMilliseconds(100), policy.GetDelay(1));
        Assert.Equal(TimeSpan.FromMilliseconds(200), policy.GetDelay(2));
        Assert.Equal(TimeSpan.FromMilliseconds(400), policy.GetDelay(3));
    }
}
