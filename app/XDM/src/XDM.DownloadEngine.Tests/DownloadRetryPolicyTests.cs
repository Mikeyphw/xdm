using System.Net;

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
    [Fact]
    public void TreatsPermanentHttpStatusesAsNonTransient()
    {
        DownloadRetryPolicy policy = new(4, TimeSpan.FromMilliseconds(10), 0);

        Assert.False(policy.IsTransient(new HttpRequestException("not found", null, HttpStatusCode.NotFound)));
        Assert.False(policy.IsTransient(new HttpRequestException("forbidden", null, HttpStatusCode.Forbidden)));
        Assert.True(policy.IsTransient(new HttpRequestException("busy", null, HttpStatusCode.ServiceUnavailable)));
        Assert.True(policy.IsTransient(new HttpRequestException("throttled", null, HttpStatusCode.TooManyRequests)));
    }

}
