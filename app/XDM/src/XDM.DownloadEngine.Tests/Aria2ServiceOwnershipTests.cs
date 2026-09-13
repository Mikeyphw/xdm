using XDM.DownloadEngine.Aria2;

namespace XDM.DownloadEngine.Tests;

public sealed class Aria2ServiceOwnershipTests
{
    [Fact]
    public async Task PagedTaskLoadingOwnsEveryWaitingOrStoppedTask()
    {
        const int Total = 2_105;
        List<(int Offset, int Count)> calls = [];

        IReadOnlyList<Aria2TaskSnapshot> tasks = await Aria2Service.LoadPagedTasksAsync(
            (offset, count, _) =>
            {
                calls.Add((offset, count));
                int take = Math.Min(count, Total - offset);
                if (take <= 0)
                {
                    return Task.FromResult<IReadOnlyList<Aria2TaskSnapshot>>([]);
                }
                Aria2TaskSnapshot[] page = Enumerable.Range(offset, take)
                    .Select(index => new Aria2TaskSnapshot(
                        index.ToString("x16"),
                        Aria2TaskStatus.Waiting,
                        $"task-{index}",
                        $"/tmp/task-{index}",
                        0,
                        1,
                        0,
                        0,
                        0,
                        null,
                        null))
                    .ToArray();
                return Task.FromResult<IReadOnlyList<Aria2TaskSnapshot>>(page);
            },
            CancellationToken.None);

        Assert.Equal(Total, tasks.Count);
        Assert.Equal(3, calls.Count);
        Assert.Equal((0, 1000), calls[0]);
        Assert.Equal((1000, 1000), calls[1]);
        Assert.Equal((2000, 1000), calls[2]);
        Assert.Equal("0000000000000838", tasks[^1].Gid);
    }
}
