using System.IO.Compression;
using System.Security.Cryptography;
using System.Text.Json;
using XDM.Core.Product;
using XDM.Updater;

namespace XDM.Core.Tests;

public sealed class UpdateTransactionExecutorTests
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web)
    {
        WriteIndented = true
    };

    [Fact]
    public async Task AppliesPortablePackageAndPreservesRollbackBackup()
    {
        string root = Path.Combine(Path.GetTempPath(), $"xdm-updater-{Guid.NewGuid():N}");
        string install = Path.Combine(root, "xdm");
        string backup = Path.Combine(root, ".xdm-backup");
        string candidate = Path.Combine(root, ".xdm-candidate");
        string package = Path.Combine(root, "update.zip");
        string transactionPath = Path.Combine(root, "transaction.json");
        Directory.CreateDirectory(install);
        await File.WriteAllTextAsync(Path.Combine(install, "old.txt"), "old");
        CreatePackage(package);
        byte[] bytes = await File.ReadAllBytesAsync(package);
        UpdateTransactionDocument transaction = new(
            1,
            "test",
            "9.0.0",
            "9.1.0",
            UpdateChannel.Stable,
            OperatingSystem.IsWindows() ? "win-x64" : "linux-x64",
            package,
            Convert.ToHexString(SHA256.HashData(bytes)),
            bytes.Length,
            install,
            backup,
            candidate,
            UpdateTransactionState.Staged,
            DateTimeOffset.UnixEpoch,
            DateTimeOffset.UnixEpoch,
            ExecutableRelativePath: OperatingSystem.IsWindows() ? "XDM.exe" : "XDM");
        await File.WriteAllTextAsync(transactionPath, JsonSerializer.Serialize(transaction, JsonOptions));

        try
        {
            UpdateTransactionExecutor executor = new(launchApplication: false);
            await executor.ApplyAsync(transactionPath);

            Assert.True(Directory.Exists(backup));
            Assert.True(File.Exists(Path.Combine(backup, "old.txt")));
            string installedExecutable = Path.Combine(install, OperatingSystem.IsWindows() ? "XDM.exe" : "XDM");
            Assert.True(File.Exists(installedExecutable));
            if (!OperatingSystem.IsWindows())
            {
                Assert.NotEqual(
                    0,
                    (int)(File.GetUnixFileMode(installedExecutable) & UnixFileMode.UserExecute));
                Assert.NotEqual(
                    0,
                    (int)(File.GetUnixFileMode(Path.Combine(install, "XDM.NativeHost")) & UnixFileMode.UserExecute));
                Assert.NotEqual(
                    0,
                    (int)(File.GetUnixFileMode(Path.Combine(install, "XDM.Updater")) & UnixFileMode.UserExecute));
            }
            UpdateTransactionDocument applied = JsonSerializer.Deserialize<UpdateTransactionDocument>(
                await File.ReadAllTextAsync(transactionPath),
                JsonOptions)!;
            Assert.Equal(UpdateTransactionState.AppliedPendingHealth, applied.State);

            await UpdateTransactionExecutor.MarkHealthyAsync(transactionPath);
            UpdateTransactionDocument healthy = JsonSerializer.Deserialize<UpdateTransactionDocument>(
                await File.ReadAllTextAsync(transactionPath),
                JsonOptions)!;
            Assert.Equal(UpdateTransactionState.Healthy, healthy.State);
            Assert.False(Directory.Exists(backup));
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
    public async Task RejectsArchivePathTraversalBeforeReplacingInstallation()
    {
        string root = Path.Combine(Path.GetTempPath(), $"xdm-updater-{Guid.NewGuid():N}");
        string install = Path.Combine(root, "xdm");
        string package = Path.Combine(root, "unsafe.zip");
        string transactionPath = Path.Combine(root, "transaction.json");
        Directory.CreateDirectory(install);
        using (ZipArchive archive = ZipFile.Open(package, ZipArchiveMode.Create))
        {
            ZipArchiveEntry entry = archive.CreateEntry("../escape.txt");
            await using StreamWriter writer = new(entry.Open());
            await writer.WriteAsync("unsafe");
        }
        byte[] bytes = await File.ReadAllBytesAsync(package);
        UpdateTransactionDocument transaction = new(
            1,
            "test",
            "9.0.0",
            "9.1.0",
            UpdateChannel.Stable,
            "linux-x64",
            package,
            Convert.ToHexString(SHA256.HashData(bytes)),
            bytes.Length,
            install,
            Path.Combine(root, ".backup"),
            Path.Combine(root, ".candidate"),
            UpdateTransactionState.Staged,
            DateTimeOffset.UnixEpoch,
            DateTimeOffset.UnixEpoch);
        await File.WriteAllTextAsync(transactionPath, JsonSerializer.Serialize(transaction, JsonOptions));

        try
        {
            UpdateTransactionExecutor executor = new();
            await Assert.ThrowsAsync<InvalidDataException>(() => executor.ApplyAsync(transactionPath));
            Assert.True(Directory.Exists(install));
            Assert.False(File.Exists(Path.Combine(root, "escape.txt")));
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
    public async Task RejectsInvalidTransactionMetadataBeforeChangingInstallation()
    {
        string root = Path.Combine(Path.GetTempPath(), $"xdm-updater-invalid-{Guid.NewGuid():N}");
        string install = Path.Combine(root, "xdm");
        string transactionPath = Path.Combine(root, "transaction.json");
        Directory.CreateDirectory(install);
        await File.WriteAllTextAsync(Path.Combine(install, "old.txt"), "old");
        UpdateTransactionDocument transaction = new(
            99,
            "invalid",
            "9.0.0",
            "9.1.0",
            UpdateChannel.Stable,
            "linux-x64",
            Path.Combine(root, "missing.zip"),
            "not-a-hash",
            1,
            install,
            Path.Combine(root, ".backup"),
            Path.Combine(root, ".candidate"),
            UpdateTransactionState.Staged,
            DateTimeOffset.UnixEpoch,
            DateTimeOffset.UnixEpoch);
        await File.WriteAllTextAsync(transactionPath, JsonSerializer.Serialize(transaction, JsonOptions));

        try
        {
            UpdateTransactionExecutor executor = new(launchApplication: false);
            await Assert.ThrowsAsync<InvalidDataException>(() => executor.ApplyAsync(transactionPath));
            Assert.True(File.Exists(Path.Combine(install, "old.txt")));
        }
        finally
        {
            if (Directory.Exists(root))
            {
                Directory.Delete(root, recursive: true);
            }
        }
    }

    private static void CreatePackage(string path)
    {
        using ZipArchive archive = ZipFile.Open(path, ZipArchiveMode.Create);
        WriteEntry(archive, OperatingSystem.IsWindows() ? "XDM.exe" : "XDM", "new app");
        WriteEntry(archive, OperatingSystem.IsWindows() ? "XDM.NativeHost.exe" : "XDM.NativeHost", "new host");
        WriteEntry(archive, OperatingSystem.IsWindows() ? "XDM.Updater.exe" : "XDM.Updater", "new updater");
    }

    private static void WriteEntry(ZipArchive archive, string name, string content)
    {
        ZipArchiveEntry entry = archive.CreateEntry(name);
        using StreamWriter writer = new(entry.Open());
        writer.Write(content);
    }
}
