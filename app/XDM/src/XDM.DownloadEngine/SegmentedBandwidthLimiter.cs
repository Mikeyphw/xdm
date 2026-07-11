using System.Diagnostics;

namespace XDM.DownloadEngine;

internal sealed class SegmentedBandwidthLimiter(long bytesPerSecond)
{
    private readonly Stopwatch _elapsed = Stopwatch.StartNew();
    private long _bytes;

    public async ValueTask ThrottleAsync(int bytes, CancellationToken cancellationToken)
    {
        if (bytesPerSecond <= 0)
        {
            return;
        }

        long total = Interlocked.Add(ref _bytes, bytes);
        TimeSpan expected = TimeSpan.FromSeconds((double)total / bytesPerSecond);
        TimeSpan delay = expected - _elapsed.Elapsed;
        if (delay > TimeSpan.FromMilliseconds(2))
        {
            await Task.Delay(delay, cancellationToken).ConfigureAwait(false);
        }
    }
}
