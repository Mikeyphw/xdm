using XDM.App.Services;

namespace XDM.App.Tests;

public sealed class BulkOperationExecutorTests
{
    [Fact]
    public async Task PartialFailureContinuesAndCountsResults()
    {
        int[] items = [1, 2, 3, 4];
        List<int> visited = [];

        BulkOperationResult result = await BulkOperationExecutor.ExecuteAsync(
            items,
            item => item != 2,
            item =>
            {
                visited.Add(item);
                return item == 3
                    ? Task.FromException(new InvalidOperationException("failed"))
                    : Task.CompletedTask;
            });

        Assert.Equal([1, 3, 4], visited);
        Assert.Equal(2, result.Succeeded);
        Assert.Equal(1, result.Skipped);
        Assert.Equal(1, result.Failed);
        Assert.Single(result.Errors);
    }
}
