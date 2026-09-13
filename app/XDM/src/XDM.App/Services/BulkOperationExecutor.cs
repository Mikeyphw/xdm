namespace XDM.App.Services;

public sealed record BulkOperationResult(int Succeeded, int Skipped, int Failed, IReadOnlyList<string> Errors)
{
    public int Attempted => Succeeded + Failed;
}

public static class BulkOperationExecutor
{
    public static async Task<BulkOperationResult> ExecuteAsync<T>(
        IEnumerable<T> items,
        Func<T, bool> isEligible,
        Func<T, Task> action)
    {
        ArgumentNullException.ThrowIfNull(items);
        ArgumentNullException.ThrowIfNull(isEligible);
        ArgumentNullException.ThrowIfNull(action);

        int succeeded = 0;
        int skipped = 0;
        int failed = 0;
        List<string> errors = [];
        foreach (T item in items)
        {
            if (!isEligible(item))
            {
                skipped++;
                continue;
            }

            try
            {
                await action(item).ConfigureAwait(false);
                succeeded++;
            }
            catch (Exception exception) when (exception is ArgumentException
                or IOException
                or UnauthorizedAccessException
                or InvalidOperationException)
            {
                failed++;
                errors.Add(exception.Message);
            }
        }

        return new(succeeded, skipped, failed, errors);
    }
}
