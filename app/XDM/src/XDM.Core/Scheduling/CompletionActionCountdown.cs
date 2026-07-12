namespace XDM.Core.Scheduling;

public sealed class CompletionActionCountdown
{
    public static async Task RunAsync(
        int seconds,
        Func<int, Task> tick,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(tick);
        ArgumentOutOfRangeException.ThrowIfNegative(seconds);

        for (int remaining = seconds; remaining > 0; remaining--)
        {
            await tick(remaining).ConfigureAwait(false);
            await Task.Delay(TimeSpan.FromSeconds(1), cancellationToken).ConfigureAwait(false);
        }

        await tick(0).ConfigureAwait(false);
    }
}
