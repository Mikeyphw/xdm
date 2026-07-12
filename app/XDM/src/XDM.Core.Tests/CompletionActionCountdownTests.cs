using XDM.Core.Scheduling;

namespace XDM.Core.Tests;

public sealed class CompletionActionCountdownTests
{
    [Fact]
    public async Task ZeroSecondCountdownPublishesExecutionTick()
    {
        List<int> ticks = [];
        await CompletionActionCountdown.RunAsync(0, remaining =>
        {
            ticks.Add(remaining);
            return Task.CompletedTask;
        });

        Assert.Single(ticks);
        Assert.Equal(0, ticks[0]);
    }
}
