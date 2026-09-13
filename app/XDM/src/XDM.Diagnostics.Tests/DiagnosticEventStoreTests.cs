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

    [Fact]
    public void PersistentStoreRestoresRedactedCrashSafeRing()
    {
        string path = Path.Combine(Path.GetTempPath(), $"xdm-events-{Guid.NewGuid():N}.json");
        try
        {
            DiagnosticEventStore first = new(path);
            first.Record(
                DiagnosticSeverity.Error,
                "XDM-CRASH-TEST",
                "https://user:pass@example.test/file?access_token=secret",
                new Dictionary<string, string?>(StringComparer.Ordinal)
                {
                    ["path"] = "/home/tester/private/file.txt"
                });

            DiagnosticEventStore second = new(path);
            DiagnosticEvent item = Assert.Single(second.Snapshot());

            Assert.DoesNotContain("user:pass", item.Message, StringComparison.Ordinal);
            Assert.DoesNotContain("access_token=secret", item.Message, StringComparison.Ordinal);
            Assert.DoesNotContain("/home/tester/private", item.Context["path"] ?? string.Empty, StringComparison.Ordinal);
        }
        finally
        {
            File.Delete(path);
        }
    }
}
