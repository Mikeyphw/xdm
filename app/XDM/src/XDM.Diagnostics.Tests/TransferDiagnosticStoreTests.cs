using XDM.Core.Diagnostics;

namespace XDM.Diagnostics.Tests;

public sealed class TransferDiagnosticStoreTests
{
    [Fact]
    public void SnapshotFiltersByDownloadAndRedactsSecrets()
    {
        TransferDiagnosticStore store = new();
        store.Record(
            "download-a",
            TransferDiagnosticStage.Http,
            TransferDiagnosticSeverity.Information,
            "XDM-TEST-HTTP",
            "Authorization: Bearer super-secret",
            new Dictionary<string, string?>(StringComparer.Ordinal)
            {
                ["url"] = "https://example.test/file?token=secret-value",
                ["safe"] = "visible"
            });
        store.Record(
            "download-b",
            TransferDiagnosticStage.Disk,
            TransferDiagnosticSeverity.Warning,
            "XDM-TEST-DISK",
            "Disk warning");

        TransferDiagnosticEvent item = Assert.Single(store.Snapshot("download-a"));
        Assert.DoesNotContain("super-secret", item.Message, StringComparison.Ordinal);
        Assert.Contains("[REDACTED]", item.Message, StringComparison.Ordinal);
        Assert.DoesNotContain("secret-value", item.Context["url"] ?? string.Empty, StringComparison.Ordinal);
        Assert.Equal("visible", item.Context["safe"]);
    }

    [Fact]
    public void ClearCanRemoveOnlyOneTransferTimeline()
    {
        TransferDiagnosticStore store = new();
        store.Record("download-a", TransferDiagnosticStage.Scheduling, TransferDiagnosticSeverity.Information, "A", "A");
        store.Record("download-b", TransferDiagnosticStage.Scheduling, TransferDiagnosticSeverity.Information, "B", "B");

        store.Clear("download-a");

        Assert.Empty(store.Snapshot("download-a"));
        Assert.Single(store.Snapshot("download-b"));
    }

    [Fact]
    public void PersistentStoreRestoresTransferTimelineWithoutSecrets()
    {
        string path = Path.Combine(Path.GetTempPath(), $"xdm-transfer-{Guid.NewGuid():N}.json");
        try
        {
            TransferDiagnosticStore first = new(path);
            first.Record(
                "download-a",
                TransferDiagnosticStage.Http,
                TransferDiagnosticSeverity.Warning,
                "XDM-TRANSFER-TEST",
                "Cookie: session=secret",
                new Dictionary<string, string?>(StringComparer.Ordinal)
                {
                    ["url"] = "https://user:pass@example.test/file?api_key=secret"
                });

            TransferDiagnosticStore second = new(path);
            TransferDiagnosticEvent item = Assert.Single(second.Snapshot("download-a"));

            Assert.DoesNotContain("session=secret", item.Message, StringComparison.Ordinal);
            Assert.DoesNotContain("user:pass", item.Context["url"] ?? string.Empty, StringComparison.Ordinal);
            Assert.DoesNotContain("api_key=secret", item.Context["url"] ?? string.Empty, StringComparison.Ordinal);
        }
        finally
        {
            File.Delete(path);
        }
    }
}
