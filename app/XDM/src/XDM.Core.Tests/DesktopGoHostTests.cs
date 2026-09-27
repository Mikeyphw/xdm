using System.Text.Json;
using XDM.Platform;

namespace XDM.Core.Tests;

public sealed class DesktopGoHostTests
{
    [Fact]
    public async Task PublicationBrokerIsIdempotentAcrossRepeatedCommit()
    {
        string root = Path.Combine(Path.GetTempPath(), "xdm-go-publication-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        try
        {
            string journal = Path.Combine(root, "journal.json");
            string destination = Path.Combine(root, "final.bin");
            string staging = Path.Combine(root, "first.part");
            await File.WriteAllTextAsync(staging, "payload");
            DesktopGoPublicationBroker broker = new(journal);
            DesktopPublicationRequest request = Request(staging, destination, "publish:test:1");

            DesktopPublicationReceipt first = await broker.CommitAsync(request);
            DesktopPublicationReceipt second = await broker.CommitAsync(request);

            Assert.Equal(first, second);
            Assert.Equal("payload", await File.ReadAllTextAsync(destination));
            Assert.False(File.Exists(staging));
        }
        finally
        {
            Directory.Delete(root, recursive: true);
        }
    }

    [Fact]
    public async Task PublicationBrokerReconcilesCrashAfterRenameWithoutRepublishing()
    {
        string root = Path.Combine(Path.GetTempPath(), "xdm-go-publication-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        try
        {
            string journal = Path.Combine(root, "journal.json");
            string destination = Path.Combine(root, "final.bin");
            string staging = Path.Combine(root, "missing.part");
            await File.WriteAllTextAsync(destination, "already-published");
            await File.WriteAllTextAsync(journal, JsonSerializer.Serialize(new
            {
                schemaVersion = 1,
                entries = new[]
                {
                    new
                    {
                        publicationId = "pub_00000000000000000000000000000077",
                        idempotencyKey = "publish:test:77",
                        receiptId = "desktop-receipt:existing",
                        location = destination,
                        status = "pending"
                    }
                }
            }, new JsonSerializerOptions(JsonSerializerDefaults.Web) { WriteIndented = true }));
            DesktopGoPublicationBroker broker = new(journal);
            DesktopPublicationReceipt receipt = await broker.CommitAsync(Request(staging, destination, "publish:test:77"));

            Assert.Equal("desktop-receipt:existing", receipt.ReceiptId);
            Assert.Equal("already-published", await File.ReadAllTextAsync(destination));
        }
        finally
        {
            Directory.Delete(root, recursive: true);
        }
    }

    [Fact]
    public async Task PublicationBrokerReusesJournaledUniqueDestinationAfterPreMoveCrash()
    {
        string root = Path.Combine(Path.GetTempPath(), "xdm-go-publication-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        try
        {
            string journal = Path.Combine(root, "journal.json");
            string destination = Path.Combine(root, "final.bin");
            string journaledDestination = Path.Combine(root, "final (1).bin");
            string staging = Path.Combine(root, "pending.part");
            await File.WriteAllTextAsync(destination, "collision");
            await File.WriteAllTextAsync(staging, "payload");
            await File.WriteAllTextAsync(journal, JsonSerializer.Serialize(new
            {
                schemaVersion = 1,
                entries = new[]
                {
                    new
                    {
                        publicationId = "pub_00000000000000000000000000000077",
                        idempotencyKey = "publish:test:pending",
                        receiptId = "desktop-receipt:pending",
                        location = journaledDestination,
                        status = "pending"
                    }
                }
            }, new JsonSerializerOptions(JsonSerializerDefaults.Web) { WriteIndented = true }));
            DesktopGoPublicationBroker broker = new(journal);
            DesktopPublicationRequest request = Request(staging, destination, "publish:test:pending") with { CollisionPolicy = "unique" };

            DesktopPublicationReceipt receipt = await broker.CommitAsync(request);

            Assert.Equal(journaledDestination, receipt.Location);
            Assert.Equal("payload", await File.ReadAllTextAsync(journaledDestination));
            Assert.Equal("collision", await File.ReadAllTextAsync(destination));
        }
        finally
        {
            Directory.Delete(root, recursive: true);
        }
    }

    [Fact]
    public async Task PublicationBrokerRejectsIdempotencyKeyReboundToDifferentPublication()
    {
        string root = Path.Combine(Path.GetTempPath(), "xdm-go-publication-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        try
        {
            string journal = Path.Combine(root, "journal.json");
            string destination = Path.Combine(root, "final.bin");
            string staging = Path.Combine(root, "pending.part");
            await File.WriteAllTextAsync(staging, "payload");
            await File.WriteAllTextAsync(journal, JsonSerializer.Serialize(new
            {
                schemaVersion = 1,
                entries = new[]
                {
                    new
                    {
                        publicationId = "pub_00000000000000000000000000000088",
                        idempotencyKey = "publish:test:collision",
                        receiptId = "desktop-receipt:collision",
                        location = destination,
                        status = "pending"
                    }
                }
            }, new JsonSerializerOptions(JsonSerializerDefaults.Web) { WriteIndented = true }));
            DesktopGoPublicationBroker broker = new(journal);

            await Assert.ThrowsAsync<InvalidDataException>(() =>
                broker.CommitAsync(Request(staging, destination, "publish:test:collision")));
        }
        finally
        {
            Directory.Delete(root, recursive: true);
        }
    }

    [Fact]
    public void DesktopPackagingAndStartupCarryXdmcoreHostBoundary()
    {
        string root = FindRepositoryRoot(AppContext.BaseDirectory);
        string app = File.ReadAllText(Path.Combine(root, "app", "XDM", "src", "XDM.App", "App.axaml.cs"));
        string host = File.ReadAllText(Path.Combine(root, "app", "XDM", "src", "XDM.Platform", "DesktopGoEngineHost.cs"));
        foreach (string script in new[] { "publish-one.sh", "publish-modern.sh", "smoke-package.sh" })
        {
            Assert.Contains("build-xgo-native.sh", File.ReadAllText(Path.Combine(root, "app", "XDM", "eng", script)), StringComparison.Ordinal);
        }
        foreach (string script in new[] { "publish-one.ps1", "publish-modern.ps1", "smoke-package.ps1" })
        {
            Assert.Contains("build-xgo-native.ps1", File.ReadAllText(Path.Combine(root, "app", "XDM", "eng", script)), StringComparison.Ordinal);
        }
        Assert.Contains("DesktopGoEngineHost", app, StringComparison.Ordinal);
        Assert.Contains("xdm_engine_create", host, StringComparison.Ordinal);
        Assert.Contains("xdm_engine_platform_reply", host, StringComparison.Ordinal);
        Assert.Contains("engine_version", host, StringComparison.Ordinal);
        Assert.Contains("schema_version", host, StringComparison.Ordinal);
        Assert.Contains("ExpectedEngineVersion = \"0.1.0-dev\"", host, StringComparison.Ordinal);
        Assert.Contains("ExpectedProtocolMajor = 1", host, StringComparison.Ordinal);
        Assert.Contains("ExpectedProtocolMinor = 0", host, StringComparison.Ordinal);
        Assert.Contains("ExpectedAbiMajor = 1", host, StringComparison.Ordinal);
        Assert.Contains("ExpectedSchemaVersion = 0", host, StringComparison.Ordinal);
        Assert.DoesNotContain("IDownloadManager", host, StringComparison.Ordinal);
    }

    private static DesktopPublicationRequest Request(string staging, string destination, string key) => new(
        new DesktopPublicationCommit(
            "pub_00000000000000000000000000000077",
            "dl_00000000000000000000000000000077",
            1,
            key,
            "stage:desktop:77"),
        staging,
        destination,
        "replace");

    private static string FindRepositoryRoot(string startPath)
    {
        DirectoryInfo? current = new(Path.GetFullPath(startPath));
        while (current is not null)
        {
            if (File.Exists(Path.Combine(current.FullName, ".devtool.toml"))
                && File.Exists(Path.Combine(current.FullName, "app", "XDM", "XDM.Modern.sln")))
            {
                return current.FullName;
            }
            current = current.Parent;
        }
        throw new DirectoryNotFoundException("Repository root not found.");
    }
}
