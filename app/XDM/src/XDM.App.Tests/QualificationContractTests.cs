namespace XDM.App.Tests;

public sealed class QualificationContractTests
{
    private static string RepositoryRoot()
    {
        DirectoryInfo? current = new(AppContext.BaseDirectory);
        while (current is not null)
        {
            if (File.Exists(Path.Combine(current.FullName, ".devtool.toml"))) return current.FullName;
            current = current.Parent;
        }
        throw new DirectoryNotFoundException();
    }

    [Fact]
    public void BootstrapContractRequiresAllNineNavigationSections()
    {
        string source = File.ReadAllText(Path.Combine(RepositoryRoot(), "app/XDM/src/XDM.App/Program.cs"));
        Assert.Contains("expectedSections", source, StringComparison.Ordinal);
        foreach (string id in new[] { "downloads", "recovery", "queues", "scheduler", "browser", "media", "conversion", "settings", "diagnostics" })
            Assert.Contains($"\\\"{id}\\\"", source, StringComparison.Ordinal);
        Assert.DoesNotContain("Sections.Count == 8", source, StringComparison.Ordinal);
    }

    [Fact]
    public void QualificationEnforcesWarningsAndNativeExitCodes()
    {
        string root = RepositoryRoot();
        string props = File.ReadAllText(Path.Combine(root, "app/XDM/src/Directory.Build.props"));
        string ps = File.ReadAllText(Path.Combine(root, "app/XDM/eng/validate-modern.ps1"));
        string sh = File.ReadAllText(Path.Combine(root, "app/XDM/eng/validate-modern.sh"));
        Assert.Contains("<TreatWarningsAsErrors>true</TreatWarningsAsErrors>", props, StringComparison.Ordinal);
        Assert.Contains("$LASTEXITCODE", ps, StringComparison.Ordinal);
        Assert.Contains("-warnaserror", ps, StringComparison.Ordinal);
        Assert.Contains("set -euo pipefail", sh, StringComparison.Ordinal);
        Assert.Contains("-warnaserror", sh, StringComparison.Ordinal);
    }

    [Fact]
    public void CiPackageQualificationNamesTheReleaseRidMatrix()
    {
        string workflow = File.ReadAllText(Path.Combine(RepositoryRoot(), ".github/workflows/modern-ci.yml"));
        foreach (string rid in new[] { "linux-x64", "linux-arm64", "win-x64", "win-arm64" })
            Assert.Contains(rid, workflow, StringComparison.Ordinal);
    }
}
