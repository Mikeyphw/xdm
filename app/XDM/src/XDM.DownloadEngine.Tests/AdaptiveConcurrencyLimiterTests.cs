using XDM.DownloadEngine.Policies;

namespace XDM.DownloadEngine.Tests;

public sealed class AdaptiveConcurrencyLimiterTests
{
    [Fact]
    public async Task WaitsUntilAHostSlotIsReleased()
    {
        AdaptiveConcurrencyLimiter limiter = new();
        using IDisposable first = await limiter.AcquireAsync("example.test", 1, CancellationToken.None);
        Task<IDisposable> second = limiter
            .AcquireAsync("example.test", 1, CancellationToken.None)
            .AsTask();

        Assert.False(second.IsCompleted);

        first.Dispose();
        using IDisposable acquired = await second.WaitAsync(TimeSpan.FromSeconds(1));
    }

    [Fact]
    public async Task IncreasedLimitWakesWaitingTransfers()
    {
        AdaptiveConcurrencyLimiter limiter = new();
        int limit = 1;
        using IDisposable first = await limiter.AcquireAsync(
            "example.test",
            () => limit,
            CancellationToken.None);
        Task<IDisposable> second = limiter
            .AcquireAsync(
                "example.test",
                () => limit,
                CancellationToken.None)
            .AsTask();
        Assert.False(second.IsCompleted);

        limit = 2;
        limiter.NotifyLimitsChanged();

        using IDisposable acquired = await second.WaitAsync(TimeSpan.FromSeconds(1));
    }

    [Fact]
    public async Task DifferentHostsUseIndependentSlots()
    {
        AdaptiveConcurrencyLimiter limiter = new();
        using IDisposable first = await limiter.AcquireAsync("one.test", 1, CancellationToken.None);

        using IDisposable second = await limiter.AcquireAsync("two.test", 1, CancellationToken.None);
    }
}
