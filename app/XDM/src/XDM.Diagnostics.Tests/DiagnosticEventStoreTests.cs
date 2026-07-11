using XDM.Diagnostics;

namespace XDM.Diagnostics.Tests;

public sealed class DiagnosticEventStoreTests
{
    [Fact]
    public void StoreIsBoundedAndRedactsMessages()
    {
        DiagnosticEventStore store = new();
        for (int index = 0; index < 505; index++)
        {
            store.Record(DiagnosticSeverity.Information, "XDM-TEST", $"X-XDM-Token: value-{index}");
        }

        IReadOnlyList<DiagnosticEvent> events = store.Snapshot();
        Assert.Equal(500, events.Count);
        Assert.All(events, item => Assert.DoesNotContain("value-", item.Message, StringComparison.Ordinal));
    }
}
