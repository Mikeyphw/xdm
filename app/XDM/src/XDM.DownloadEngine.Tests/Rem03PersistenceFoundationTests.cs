using System.Text.Json;
using XDM.Core.Downloads;
using XDM.Core.Persistence;
using XDM.Core.Settings;
using XDM.Persistence;

namespace XDM.DownloadEngine.Tests;

public sealed class Rem03PersistenceFoundationTests
{
    [Fact]
    public async Task SettingsFutureSchemaIsQuarantinedAndNeverSilentlyDowngraded()
    {
        using TemporaryDirectory directory = new();
        string path = Path.Combine(directory.Path, "settings.json");
        JsonSettingsStore store = new(path);
        ApplicationSettings original = ApplicationSettings.CreateDefault();

        await store.SaveAsync(original);
        await store.SaveAsync(original with { ClipboardMonitoringEnabled = true });
        await File.WriteAllTextAsync(
            path,
            JsonSerializer.Serialize(original with { SchemaVersion = ApplicationSettings.CurrentSchemaVersion + 100 }));

        await Assert.ThrowsAsync<InvalidDataException>(() => store.LoadAsync());
        Assert.Contains(
            Directory.EnumerateFiles(directory.Path),
            file => file.Contains(".incompatible-", StringComparison.Ordinal));
    }

    [Fact]
    public async Task SettingsCorruptionRecoversLastGoodBackup()
    {
        using TemporaryDirectory directory = new();
        string path = Path.Combine(directory.Path, "settings.json");
        JsonSettingsStore store = new(path);
        ApplicationSettings original = ApplicationSettings.CreateDefault();

        await store.SaveAsync(original);
        await store.SaveAsync(original with { ClipboardMonitoringEnabled = true });
        await File.WriteAllTextAsync(path, "{ definitely-not-json");

        ApplicationSettings? recovered = await store.LoadAsync();

        Assert.NotNull(recovered);
        Assert.False(recovered!.ClipboardMonitoringEnabled);
    }

    [Fact]
    public async Task HistoryRejectsDuplicateDestinationOwnership()
    {
        using TemporaryDirectory directory = new();
        string path = Path.Combine(directory.Path, "downloads.json");
        string destination = Path.Combine(directory.Path, "same.bin");
        PersistedDownload first = new(
            "one",
            new Uri("https://example.test/one"),
            destination,
            0,
            100,
            DownloadState.Paused,
            DateTimeOffset.UtcNow);
        PersistedDownload second = first with
        {
            Id = "two",
            Source = new Uri("https://example.test/two")
        };
        await File.WriteAllTextAsync(path, JsonSerializer.Serialize(new[] { first, second }));

        JsonDownloadHistoryStore store = new(path);

        await Assert.ThrowsAsync<InvalidDataException>(() => store.LoadAsync());
    }

    [Fact]
    public async Task RedactedSettingsImportPreservesExistingSecrets()
    {
        using TemporaryDirectory directory = new();
        string path = Path.Combine(directory.Path, "settings.json");
        ApplicationSettings baseline = ApplicationSettings.CreateDefault() with
        {
            Network = NetworkSettings.Default with
            {
                Proxy = new ProxySettings(
                    ProxyMode.Manual,
                    "proxy.example.test",
                    8080,
                    "alice",
                    "proxy-secret",
                    true,
                    [])
            },
            Credentials =
            [
                new ServerCredentialDefinition("example.test", "alice", "credential-secret", false)
            ],
            Aria2 = Aria2IntegrationSettings.Default with
            {
                RpcSecret = "rpc-secret"
            }
        };
        SettingsTransferService service = new();

        await service.ExportAsync(path, baseline, includeSecrets: false);
        SettingsImportResult imported = await service.ImportAsync(path, baseline);

        Assert.Equal("proxy-secret", imported.Settings.Network!.Proxy!.Password);
        Assert.Equal("credential-secret", Assert.Single(imported.Settings.Credentials!).Password);
        Assert.Equal("rpc-secret", imported.Settings.Aria2!.RpcSecret);
    }

    [Fact]
    public async Task JavaPropertiesContinuationAndEscapesAreParsed()
    {
        using TemporaryDirectory directory = new();
        string path = Path.Combine(directory.Path, "settings.properties");
        await File.WriteAllTextAsync(
            path,
            "defaultDownloadDirectory=/tmp/downloads\n" +
            "proxyMode=manual\n" +
            "proxyHost=proxy\\u002Eexample\\u002Etest\n" +
            "proxyUsername=ali\\\n  ce\n" +
            "proxyPassword=s3cr\\:et\n");

        SettingsTransferService service = new();
        SettingsImportResult imported = await service.ImportAsync(path, ApplicationSettings.CreateDefault());

        Assert.Equal("proxy.example.test", imported.Settings.Network!.Proxy!.Host);
        Assert.Equal("alice", imported.Settings.Network.Proxy.Username);
        Assert.Equal("s3cr:et", imported.Settings.Network.Proxy.Password);
    }

    [Fact]
    public async Task DownloadListExportRemovesUserInfoQueryAndFragmentAndNormalizesPriority()
    {
        using TemporaryDirectory directory = new();
        string path = Path.Combine(directory.Path, "downloads.json");
        DownloadListTransferService service = new();
        DownloadSnapshot snapshot = new(
            "one",
            "file.bin",
            new Uri("https://user:pass@example.test/file.bin?token=secret#fragment"),
            Path.Combine(directory.Path, "file.bin"),
            0,
            100,
            0,
            DownloadState.Paused,
            DateTimeOffset.UtcNow);

        await service.ExportAsync(path, [snapshot]);
        DownloadListImportResult imported = await service.ImportAsync(path);

        DownloadListEntry entry = Assert.Single(imported.Downloads);
        Assert.Equal("https://example.test/file.bin", entry.Source.AbsoluteUri);

        string raw = await File.ReadAllTextAsync(path);
        Assert.DoesNotContain("pass", raw, StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain("token", raw, StringComparison.OrdinalIgnoreCase);

        string invalidPriorityPath = Path.Combine(directory.Path, "invalid-priority.json");
        await File.WriteAllTextAsync(
            invalidPriorityPath,
            """{"schemaVersion":4,"exportedAt":"2026-09-13T00:00:00Z","downloads":[{"source":"https://example.test/file","priority":999}]}""");
        DownloadListImportResult normalized = await service.ImportAsync(invalidPriorityPath);
        Assert.Equal(DownloadPriority.Normal, Assert.Single(normalized.Downloads).Priority);
    }

    private sealed class TemporaryDirectory : IDisposable
    {
        public TemporaryDirectory()
        {
            Path = System.IO.Path.Combine(
                System.IO.Path.GetTempPath(),
                $"xdm-rem03-{Guid.NewGuid():N}");
            Directory.CreateDirectory(Path);
        }

        public string Path { get; }

        public void Dispose()
        {
            if (Directory.Exists(Path))
            {
                Directory.Delete(Path, recursive: true);
            }
        }
    }
}
