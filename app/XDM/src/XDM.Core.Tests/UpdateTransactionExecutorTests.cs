using System.Diagnostics;
using System.IO.Compression;
using System.Security.Cryptography;
using System.Text.Json;
using XDM.Core.Product;
using XDM.Updater;

namespace XDM.Core.Tests;

public sealed class UpdateTransactionExecutorTests
{
    private static readonly JsonSerializerOptions SerializerOptions = new(JsonSerializerDefaults.Web)
    {
        WriteIndented = true
    };

    [Fact]
    public async Task RestoresBackupWhenInterruptedApplyLeftInstallRootAbsent()
    {
        string root = Path.Combine(Path.GetTempPath(), $"xdm-update-interrupted-{Guid.NewGuid():N}");
        string install = Path.Combine(root, "xdm");
        string backup = Path.Combine(root, ".xdm-rollback-restore");
        string candidate = Path.Combine(root, ".xdm-candidate-restore");
        string package = Path.Combine(root, "package.zip");
        string transactionPath = Path.Combine(root, "update-transaction.json");
        Directory.CreateDirectory(root);
        Directory.CreateDirectory(backup);
        await File.WriteAllTextAsync(Path.Combine(backup, OperatingSystem.IsWindows() ? "XDM.exe" : "XDM"), "previous");
        await File.WriteAllBytesAsync(package, [1, 2, 3]);
        string sha256 = Convert.ToHexString(SHA256.HashData(await File.ReadAllBytesAsync(package)));
        UpdateTransactionDocument transaction = new(
            2,
            "restore",
            "9.0.0",
            "9.1.0",
            UpdateChannel.Stable,
            OperatingSystem.IsWindows() ? "win-x64" : "linux-x64",
            package,
            sha256,
            new FileInfo(package).Length,
            install,
            backup,
            candidate,
            UpdateTransactionState.Applying,
            DateTimeOffset.UtcNow,
            DateTimeOffset.UtcNow,
            InstallModel: "portable-self-update",
            RecoveryMarkerPath: Path.Combine(root, ".xdm-update-restore.json"));
        await File.WriteAllTextAsync(transactionPath, JsonSerializer.Serialize(transaction, SerializerOptions));

        try
        {
            UpdateTransactionExecutor executor = new(launchApplication: false);

            await Assert.ThrowsAsync<InvalidOperationException>(() => executor.ApplyAsync(transactionPath));

            Assert.True(Directory.Exists(install));
            Assert.False(Directory.Exists(backup));
            UpdateTransactionDocument? restored = JsonSerializer.Deserialize<UpdateTransactionDocument>(
                await File.ReadAllTextAsync(transactionPath),
                SerializerOptions);
            Assert.NotNull(restored);
            Assert.Equal(UpdateTransactionState.Failed, restored.State);
            Assert.Contains("restored", restored.FailureMessage, StringComparison.OrdinalIgnoreCase);
        }
        finally
        {
            if (Directory.Exists(root))
            {
                Directory.Delete(root, recursive: true);
            }
        }
    }

    [Fact]
    public async Task MarkHealthyWaitsUntilObservedHealthWindowExpires()
    {
        string root = Path.Combine(Path.GetTempPath(), $"xdm-update-health-window-{Guid.NewGuid():N}");
        string install = Path.Combine(root, "xdm");
        string backup = Path.Combine(root, ".xdm-rollback-health");
        string candidate = Path.Combine(root, ".xdm-candidate-health");
        string package = Path.Combine(root, "package.zip");
        string transactionPath = Path.Combine(root, "update-transaction.json");
        Directory.CreateDirectory(install);
        Directory.CreateDirectory(backup);
        Directory.CreateDirectory(candidate);
        CreatePortablePackage(package);
        string sha256 = Convert.ToHexString(SHA256.HashData(await File.ReadAllBytesAsync(package)));
        DateTimeOffset readyAt = DateTimeOffset.UtcNow.AddMilliseconds(80);
        UpdateTransactionDocument transaction = new(
            2,
            "health-window",
            "9.0.0",
            "9.1.0",
            UpdateChannel.Stable,
            OperatingSystem.IsWindows() ? "win-x64" : "linux-x64",
            package,
            sha256,
            new FileInfo(package).Length,
            install,
            backup,
            candidate,
            UpdateTransactionState.AppliedPendingHealth,
            DateTimeOffset.UtcNow,
            DateTimeOffset.UtcNow,
            ExecutableRelativePath: OperatingSystem.IsWindows() ? "XDM.exe" : "XDM",
            HealthyAfterUtc: readyAt,
            InstallModel: "portable-self-update",
            RecoveryMarkerPath: Path.Combine(root, ".xdm-update-health-window.json"));
        await File.WriteAllTextAsync(transactionPath, JsonSerializer.Serialize(transaction, SerializerOptions));
        Stopwatch stopwatch = Stopwatch.StartNew();

        try
        {
            await UpdateTransactionExecutor.MarkHealthyAsync(transactionPath);

            stopwatch.Stop();
            UpdateTransactionDocument? healthy = JsonSerializer.Deserialize<UpdateTransactionDocument>(
                await File.ReadAllTextAsync(transactionPath),
                SerializerOptions);
            Assert.NotNull(healthy);
            Assert.Equal(UpdateTransactionState.Healthy, healthy.State);
            Assert.True(stopwatch.ElapsedMilliseconds >= 40);
            Assert.False(Directory.Exists(backup));
            Assert.False(Directory.Exists(candidate));
        }
        finally
        {
            if (Directory.Exists(root))
            {
                Directory.Delete(root, recursive: true);
            }
        }
    }

    private static void CreatePortablePackage(string path)
    {
        using ZipArchive archive = ZipFile.Open(path, ZipArchiveMode.Create);
        AddFile(archive, OperatingSystem.IsWindows() ? "XDM.exe" : "XDM");
        AddFile(archive, OperatingSystem.IsWindows() ? "XDM.NativeHost.exe" : "XDM.NativeHost");
        AddFile(archive, OperatingSystem.IsWindows() ? "XDM.Updater.exe" : "XDM.Updater");
    }

    private static void AddFile(ZipArchive archive, string name)
    {
        ZipArchiveEntry entry = archive.CreateEntry(name);
        using StreamWriter writer = new(entry.Open());
        writer.Write(name);
    }
}
