using System.Globalization;
using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using XDM.Core.Product;
using XDM.Platform;

namespace XDM.Core.Tests;

public sealed class VerifiedUpdateServiceTests
{
    private static readonly JsonSerializerOptions SerializerOptions = new(JsonSerializerDefaults.Web);

    [Fact]
    public async Task ChecksAndStagesMatchingVerifiedPackage()
    {
        byte[] packageBytes = Encoding.UTF8.GetBytes("verified update payload");
        string sha256 = Convert.ToHexString(SHA256.HashData(packageBytes));
        UpdatePackageDescriptor[] packages =
        [
            new(
                "win-x64",
                "https://github.com/Mikeyphw/xdm/releases/download/v9.1.0/xdm-win-x64.zip",
                sha256,
                packageBytes.Length,
                "xdm-win-x64.zip")
        ];
        UpdateManifestDocument manifest = new(
            1,
            "9.1.0",
            "https://github.com/Mikeyphw/xdm/releases/tag/v9.1.0",
            packages);
        byte[] manifestBytes = JsonSerializer.SerializeToUtf8Bytes(
            manifest,
            SerializerOptions);
        using HttpClient client = new(new UpdateRoutingHandler(manifestBytes, packageBytes));
        string root = Path.Combine(Path.GetTempPath(), $"xdm-update-{Guid.NewGuid():N}");
        VerifiedUpdateService service = new(
            client,
            new TestPlatformInfo("Windows 11", "X64"),
            root,
            new Uri("https://github.com/Mikeyphw/xdm/releases/latest/download/xdm-update.json"));

        try
        {
            UpdateCheckResult check = await service.CheckAsync();
            StagedUpdateResult staged = await service.StageAsync(check);

            Assert.True(check.UpdateAvailable);
            Assert.NotNull(check.Package);
            Assert.Equal(sha256, staged.Sha256);
            Assert.Equal(packageBytes, await File.ReadAllBytesAsync(staged.PackagePath));
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
    public async Task RejectsPackageHashMismatchAndDeletesTemporaryFile()
    {
        byte[] packageBytes = Encoding.UTF8.GetBytes("tampered payload");
        string declaredHash = new('0', 64);
        UpdatePackageDescriptor[] packages =
        [
            new(
                "linux-x64",
                "https://github.com/Mikeyphw/xdm/releases/download/v9.1.0/xdm-linux-x64.zip",
                declaredHash,
                packageBytes.Length,
                "xdm-linux-x64.zip")
        ];
        UpdateManifestDocument manifest = new(
            1,
            "9.1.0",
            null,
            packages);
        byte[] manifestBytes = JsonSerializer.SerializeToUtf8Bytes(
            manifest,
            SerializerOptions);
        using HttpClient client = new(new UpdateRoutingHandler(manifestBytes, packageBytes));
        string root = Path.Combine(Path.GetTempPath(), $"xdm-update-{Guid.NewGuid():N}");
        VerifiedUpdateService service = new(
            client,
            new TestPlatformInfo("Linux", "X64"),
            root,
            new Uri("https://github.com/Mikeyphw/xdm/releases/latest/download/xdm-update.json"));

        try
        {
            UpdateCheckResult check = await service.CheckAsync();
            await Assert.ThrowsAsync<InvalidDataException>(() => service.StageAsync(check));
            if (Directory.Exists(root))
            {
                Assert.Empty(Directory.GetFiles(root, "*.downloading", SearchOption.AllDirectories));
            }
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
    public async Task RejectsUntrustedPackageHost()
    {
        UpdatePackageDescriptor[] packages =
        [
            new(
                "linux-x64",
                "https://attacker.example/xdm.zip",
                new string('0', 64),
                1,
                "xdm.zip")
        ];
        UpdateManifestDocument manifest = new(
            1,
            "9.1.0",
            null,
            packages);
        byte[] manifestBytes = JsonSerializer.SerializeToUtf8Bytes(
            manifest,
            SerializerOptions);
        byte[] packageBytes = [1];
        using HttpClient client = new(new UpdateRoutingHandler(manifestBytes, packageBytes));
        VerifiedUpdateService service = new(
            client,
            new TestPlatformInfo("Linux", "X64"),
            Path.GetTempPath(),
            new Uri("https://github.com/Mikeyphw/xdm/releases/latest/download/xdm-update.json"));

        await Assert.ThrowsAsync<InvalidDataException>(() => service.CheckAsync());
    }


    [Fact]
    public async Task AcceptsChannelAwareManifestAndWritesVerificationArtifacts()
    {
        byte[] packageBytes = Encoding.UTF8.GetBytes("channel-aware verified update");
        string sha256 = Convert.ToHexString(SHA256.HashData(packageBytes));
        string sha512 = Convert.ToHexString(SHA512.HashData(packageBytes));
        UpdateManifestDocument manifest = new(
            2,
            "9.2.0-beta.1",
            "https://github.com/Mikeyphw/xdm/releases/tag/beta-v9.2.0-beta.1",
            [new UpdatePackageDescriptor(
                "linux-x64",
                "https://github.com/Mikeyphw/xdm/releases/download/beta-v9.2.0-beta.1/xdm-modern-linux-x64.zip",
                sha256,
                packageBytes.Length,
                "xdm-modern-linux-x64.zip",
                sha512,
                "https://github.com/Mikeyphw/xdm/releases/download/beta-v9.2.0-beta.1/xdm-modern-linux-x64.spdx.json",
                "https://github.com/Mikeyphw/xdm/attestations")],
            "beta",
            DateTimeOffset.Parse("2026-07-12T23:00:00Z", CultureInfo.InvariantCulture),
            "9.0.0");
        byte[] manifestBytes = JsonSerializer.SerializeToUtf8Bytes(manifest, SerializerOptions);
        using HttpClient client = new(new UpdateRoutingHandler(manifestBytes, packageBytes));
        string root = Path.Combine(Path.GetTempPath(), $"xdm-update-{Guid.NewGuid():N}");
        VerifiedUpdateService service = new(
            client,
            new TestPlatformInfo("Linux", "X64"),
            root,
            new Uri("https://github.com/Mikeyphw/xdm/releases/download/beta/xdm-update-beta.json"));

        try
        {
            UpdateCheckResult check = await service.CheckAsync(UpdateChannel.Beta);
            StagedUpdateResult staged = await service.StageAsync(check);

            Assert.Equal(UpdateChannel.Beta, check.Channel);
            Assert.Equal(sha512, staged.Sha512);
            Assert.True(File.Exists(staged.ReceiptPath));
            Assert.True(File.Exists(staged.TransactionPath));
            UpdateTransactionDocument? transaction = JsonSerializer.Deserialize<UpdateTransactionDocument>(
                await File.ReadAllTextAsync(staged.TransactionPath!),
                SerializerOptions);
            Assert.NotNull(transaction);
            Assert.Equal(UpdateTransactionState.Staged, transaction.State);
            Assert.Equal("9.2.0-beta.1", transaction.TargetVersion);
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
    public async Task RejectsManifestFromDifferentChannel()
    {
        UpdateManifestDocument manifest = new(
            2,
            "9.2.0-beta.1",
            null,
            [new UpdatePackageDescriptor(
                "linux-x64",
                "https://github.com/Mikeyphw/xdm/releases/download/beta/package.zip",
                new string('0', 64),
                1,
                "package.zip")],
            "beta",
            DateTimeOffset.UtcNow);
        byte[] manifestBytes = JsonSerializer.SerializeToUtf8Bytes(manifest, SerializerOptions);
        using HttpClient client = new(new UpdateRoutingHandler(manifestBytes, [1]));
        VerifiedUpdateService service = new(
            client,
            new TestPlatformInfo("Linux", "X64"),
            Path.GetTempPath(),
            new Uri("https://github.com/Mikeyphw/xdm/releases/latest/download/xdm-update-stable.json"));

        await Assert.ThrowsAsync<InvalidDataException>(() => service.CheckAsync(UpdateChannel.Stable));
    }

    [Fact]
    public async Task MarksUpdateMandatoryBelowMinimumSupportedVersion()
    {
        UpdateManifestDocument manifest = new(
            2,
            "10.0.0",
            null,
            [new UpdatePackageDescriptor(
                "win-x64",
                "https://github.com/Mikeyphw/xdm/releases/download/v10.0.0/package.zip",
                new string('0', 64),
                1,
                "package.zip")],
            "stable",
            DateTimeOffset.UtcNow,
            "9.9.0");
        byte[] manifestBytes = JsonSerializer.SerializeToUtf8Bytes(manifest, SerializerOptions);
        using HttpClient client = new(new UpdateRoutingHandler(manifestBytes, [1]));
        VerifiedUpdateService service = new(
            client,
            new TestPlatformInfo("Windows 11", "X64"),
            Path.GetTempPath(),
            new Uri("https://github.com/Mikeyphw/xdm/releases/latest/download/xdm-update-stable.json"));

        UpdateCheckResult check = await service.CheckAsync(UpdateChannel.Stable);

        Assert.True(check.IsMandatory);
    }

    [Fact]
    public async Task NightlyChannelOffersDifferentBuildFromSameCoreVersion()
    {
        string currentCore = ProductVersion.Current.Split('-', 2)[0];
        UpdateManifestDocument manifest = new(
            2,
            $"{currentCore}-nightly.20991231.1",
            null,
            [new UpdatePackageDescriptor(
                "linux-x64",
                "https://github.com/Mikeyphw/xdm/releases/download/nightly/package.zip",
                new string('0', 64),
                1,
                "package.zip")],
            "nightly",
            DateTimeOffset.UtcNow);
        byte[] manifestBytes = JsonSerializer.SerializeToUtf8Bytes(manifest, SerializerOptions);
        using HttpClient client = new(new UpdateRoutingHandler(manifestBytes, [1]));
        VerifiedUpdateService service = new(
            client,
            new TestPlatformInfo("Linux", "X64"),
            Path.GetTempPath(),
            new Uri("https://github.com/Mikeyphw/xdm/releases/download/nightly/xdm-update-nightly.json"));

        UpdateCheckResult check = await service.CheckAsync(UpdateChannel.Nightly);

        Assert.True(check.UpdateAvailable);
        Assert.Equal(UpdateChannel.Nightly, check.Channel);
    }

    [Fact]
    public async Task MarksAppliedTransactionHealthyAndRemovesRollbackDirectories()
    {
        string root = Path.Combine(Path.GetTempPath(), $"xdm-update-health-{Guid.NewGuid():N}");
        string backup = Path.Combine(root, "backup");
        string candidate = Path.Combine(root, "candidate");
        string transactionDirectory = Path.Combine(root, "stable", ProductVersion.Current);
        string transactionPath = Path.Combine(transactionDirectory, "update-transaction.json");
        Directory.CreateDirectory(backup);
        Directory.CreateDirectory(candidate);
        Directory.CreateDirectory(transactionDirectory);
        UpdateTransactionDocument transaction = new(
            1,
            "health",
            "9.0.0",
            ProductVersion.Current,
            UpdateChannel.Stable,
            "linux-x64",
            Path.Combine(root, "package.zip"),
            new string('A', 64),
            1,
            Path.Combine(root, "install"),
            backup,
            candidate,
            UpdateTransactionState.AppliedPendingHealth,
            DateTimeOffset.UnixEpoch,
            DateTimeOffset.UnixEpoch);
        await File.WriteAllTextAsync(transactionPath, JsonSerializer.Serialize(transaction, SerializerOptions));
        using HttpClient client = new(new UpdateRoutingHandler([], []));
        VerifiedUpdateService service = new(
            client,
            new TestPlatformInfo("Linux", "X64"),
            root,
            new Uri("https://github.com/Mikeyphw/xdm/releases/latest/download/xdm-update-stable.json"));

        try
        {
            await service.MarkCurrentVersionHealthyAsync();

            UpdateTransactionDocument? healthy = JsonSerializer.Deserialize<UpdateTransactionDocument>(
                await File.ReadAllTextAsync(transactionPath),
                SerializerOptions);
            Assert.NotNull(healthy);
            Assert.Equal(UpdateTransactionState.Healthy, healthy.State);
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

    private sealed class UpdateRoutingHandler(byte[] manifest, byte[] package) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            byte[] payload = request.RequestUri!.AbsolutePath.Contains("xdm-update", StringComparison.Ordinal)
                ? manifest
                : package;
            HttpResponseMessage response = new(HttpStatusCode.OK)
            {
                Content = new ByteArrayContent(payload),
                RequestMessage = request
            };
            response.Content.Headers.ContentLength = payload.Length;
            return Task.FromResult(response);
        }
    }

    private sealed class TestPlatformInfo(string operatingSystem, string architecture) : IPlatformInfo
    {
        public string OperatingSystem { get; } = operatingSystem;

        public string Architecture { get; } = architecture;

        public string Runtime => ".NET test";

        public string DisplayName => $"{OperatingSystem} • {Architecture}";
    }
}
