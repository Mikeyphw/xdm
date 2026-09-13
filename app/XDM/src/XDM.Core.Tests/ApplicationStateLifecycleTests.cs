using XDM.Core.State;

namespace XDM.Core.Tests;

public sealed class ApplicationStateLifecycleTests
{
    [Fact]
    public void CoreReadinessStartsFalseAndChangesExplicitly()
    {
        ApplicationState state = new();
        Assert.False(state.Current.CoreReady);

        state.SetCoreReady(true);
        Assert.True(state.Current.CoreReady);

        state.SetCoreReady(false);
        Assert.False(state.Current.CoreReady);
    }
}
