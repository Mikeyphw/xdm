namespace XDM.Media;

internal static class FragmentRetryPolicy
{
    public static async Task<T> ExecuteAsync<T>(
        Func<CancellationToken, Task<T>> operation,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(operation);
        Exception? last = null;
        for (int attempt = 0; attempt < 5; attempt++)
        {
            try
            {
                return await operation(cancellationToken).ConfigureAwait(false);
            }
            catch (HttpRequestException exception) when (attempt < 4 && !cancellationToken.IsCancellationRequested)
            {
                last = exception;
                await DelayAsync(attempt, cancellationToken).ConfigureAwait(false);
            }
            catch (IOException exception) when (attempt < 4 && !cancellationToken.IsCancellationRequested)
            {
                last = exception;
                await DelayAsync(attempt, cancellationToken).ConfigureAwait(false);
            }
            catch (TimeoutException exception) when (attempt < 4 && !cancellationToken.IsCancellationRequested)
            {
                last = exception;
                await DelayAsync(attempt, cancellationToken).ConfigureAwait(false);
            }
        }

        throw last ?? new InvalidOperationException("Fragment retry policy failed without an exception.");
    }

    private static Task DelayAsync(int attempt, CancellationToken cancellationToken)
        => Task.Delay(TimeSpan.FromMilliseconds(250 * Math.Pow(2, attempt)), cancellationToken);
}
