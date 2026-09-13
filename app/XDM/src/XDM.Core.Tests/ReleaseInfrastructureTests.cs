using System.Text.Json;
using XDM.Core.Product;
using XDM.Core.Settings;

namespace XDM.Core.Tests;

public sealed class ReleaseInfrastructureTests
{
    [Fact]
    public void UpdateSettingsDefaultToStableAndNormalizeInvalidValues()
    {
        Assert.Equal(UpdateChannel.Stable, UpdateSettings.Default.Channel);
        UpdateSettings invalid = new((UpdateChannel)999, true, true);
        Assert.Equal(UpdateSettings.Default, invalid.Normalize());
    }

    [Theory]
    [InlineData(UpdateChannel.Stable, "xdm-update-stable.json")]
    [InlineData(UpdateChannel.Nightly, "xdm-update-nightly.json")]
    public void EveryChannelHasDedicatedHttpsManifest(UpdateChannel channel, string expectedFile)
    {
        Uri manifest = ModernFeaturePolicy.GetUpdateManifest(channel);
        Assert.Equal(Uri.UriSchemeHttps, manifest.Scheme);
        Assert.Equal("github.com", manifest.Host);
        Assert.EndsWith(expectedFile, manifest.AbsolutePath, StringComparison.Ordinal);
    }

    [Fact]
    public void ReleaseWorkflowIncludesArm64SbomAndProvenance()
    {
        string root = FindRepositoryRoot(AppContext.BaseDirectory);
        string workflow = File.ReadAllText(Path.Combine(root, ".github", "workflows", "modern-release.yml"));

        Assert.Contains("linux-arm64", workflow, StringComparison.Ordinal);
        Assert.Contains("win-arm64", workflow, StringComparison.Ordinal);
        Assert.Contains("anchore/sbom-action@v0", workflow, StringComparison.Ordinal);
        Assert.Contains("actions/attest@v4", workflow, StringComparison.Ordinal);
        Assert.Contains("attestations: write", workflow, StringComparison.Ordinal);
        Assert.Contains("id-token: write", workflow, StringComparison.Ordinal);
        Assert.Contains("sign-windows.ps1", workflow, StringComparison.Ordinal);
    }

    [Fact]
    public void ReleaseMetadataGeneratorDeclaresManifestSchemaTwo()
    {
        string root = FindRepositoryRoot(AppContext.BaseDirectory);
        string script = File.ReadAllText(Path.Combine(root, "app", "XDM", "eng", "generate-release-metadata.py"));

        Assert.Contains("\"schemaVersion\": 2", script, StringComparison.Ordinal);
        Assert.Contains("SHA256SUMS", script, StringComparison.Ordinal);
        Assert.Contains("SHA512SUMS", script, StringComparison.Ordinal);
        Assert.Contains("minimumSupportedVersion", script, StringComparison.Ordinal);
        Assert.Contains("--commit-sha", script, StringComparison.Ordinal);
        Assert.Contains("--release-tag", script, StringComparison.Ordinal);
        Assert.Contains("releaseCommitSha", script, StringComparison.Ordinal);
    }



    [Fact]
    public void ReleaseWorkflowBindsMetadataToCommitAndRequiresStableWindowsSigning()
    {
        string root = FindRepositoryRoot(AppContext.BaseDirectory);
        string workflow = File.ReadAllText(Path.Combine(root, ".github", "workflows", "modern-release.yml"));

        Assert.Contains("--commit-sha '${{ github.sha }}'", workflow, StringComparison.Ordinal);
        Assert.Contains("--release-tag '${{ needs.resolve.outputs.tag }}'", workflow, StringComparison.Ordinal);
        Assert.Contains("Stable Windows releases require WINDOWS_SIGNING_CERTIFICATE", workflow, StringComparison.Ordinal);
        Assert.Contains("verify-windows-signatures.ps1", workflow, StringComparison.Ordinal);
    }

    [Fact]
    public void DebianPackageDeclaresNativeAvaloniaAndDesktopDependencies()
    {
        string root = FindRepositoryRoot(AppContext.BaseDirectory);
        string script = File.ReadAllText(Path.Combine(root, "app", "XDM", "eng", "publish-one.sh"));

        Assert.Contains("Depends:", script, StringComparison.Ordinal);
        Assert.Contains("libgtk-3-0", script, StringComparison.Ordinal);
        Assert.Contains("libx11-6", script, StringComparison.Ordinal);
        Assert.Contains("xdg-utils", script, StringComparison.Ordinal);
    }

    [Fact]
    public void PortablePublishScriptsIncludeNativeHostAndExternalUpdater()
    {
        string root = FindRepositoryRoot(AppContext.BaseDirectory);
        string shell = File.ReadAllText(Path.Combine(root, "app", "XDM", "eng", "publish-one.sh"));
        string powerShell = File.ReadAllText(Path.Combine(root, "app", "XDM", "eng", "publish-one.ps1"));

        Assert.Contains("XDM.NativeHost", shell, StringComparison.Ordinal);
        Assert.Contains("XDM.Updater", shell, StringComparison.Ordinal);
        Assert.Contains("XDM.NativeHost", powerShell, StringComparison.Ordinal);
        Assert.Contains("XDM.Updater", powerShell, StringComparison.Ordinal);
    }

    [Fact]
    public void UpdateTransactionDocumentRoundTrips()
    {
        UpdateTransactionDocument original = new(
            1,
            "transaction",
            "9.0.0",
            "9.1.0",
            UpdateChannel.Stable,
            "linux-x64",
            "/tmp/package.zip",
            new string('A', 64),
            100,
            "/opt/xdm",
            "/tmp/backup",
            "/tmp/candidate",
            UpdateTransactionState.Staged,
            DateTimeOffset.UnixEpoch,
            DateTimeOffset.UnixEpoch);

        string json = JsonSerializer.Serialize(original);
        UpdateTransactionDocument? restored = JsonSerializer.Deserialize<UpdateTransactionDocument>(json);

        Assert.Equal(original, restored);
    }
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
