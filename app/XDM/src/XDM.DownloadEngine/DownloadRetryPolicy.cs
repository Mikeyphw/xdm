namespace XDM.DownloadEngine;

public sealed class DownloadRetryPolicy
{
    public DownloadRetryPolicy(int maximumAttempts = 3, TimeSpan? baseDelay = null, double jitterFraction = 0.2)
    {
        ArgumentOutOfRangeException.ThrowIfLessThan(maximumAttempts, 1);

        if (jitterFraction is < 0 or > 1)
        {
            throw new ArgumentOutOfRangeException(nameof(jitterFraction));
        }

        MaximumAttempts = maximumAttempts;
        BaseDelay = baseDelay ?? TimeSpan.FromMilliseconds(350);
        JitterFraction = jitterFraction;
    }

    public int MaximumAttempts { get; }

    public TimeSpan BaseDelay { get; }

    public double JitterFraction { get; }

    public TimeSpan GetDelay(int completedAttempts)
    {
        ArgumentOutOfRangeException.ThrowIfLessThan(completedAttempts, 1);

        double exponential = Math.Pow(2, Math.Min(completedAttempts - 1, 6));
        double jitter = 1 + ((Random.Shared.NextDouble() * 2 - 1) * JitterFraction);
        return TimeSpan.FromMilliseconds(BaseDelay.TotalMilliseconds * exponential * jitter);
    }

    public static bool IsTransient(Exception exception)
        => exception is HttpRequestException
            or EndOfStreamException
            or TimeoutException
            or TaskCanceledException;
}
