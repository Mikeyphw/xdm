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

    private sealed class UpdateRoutingHandler(byte[] manifest, byte[] package) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            byte[] payload = request.RequestUri!.AbsolutePath.EndsWith("xdm-update.json", StringComparison.Ordinal)
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
