using XDM.Parity;

namespace XDM.Parity.Tests;

public sealed class FinalParityGateTests
{
    private static string ManifestPath => Path.Combine(AppContext.BaseDirectory, "features.json");

    private static string RepositoryRoot
        => ParityRepositoryValidator.FindRepositoryRoot(AppContext.BaseDirectory);

    [Fact]
    public async Task CriticalAndHighParityAreOneHundredPercentQualified()
    {
        ParityManifest manifest = await ParityManifestLoader.LoadAsync(ManifestPath);
        ParitySummary summary = ParityManifestValidator.Summarize(manifest);

        Assert.Empty(ParityManifestValidator.ValidateFinalGate(manifest));
        Assert.Equal(1d, summary.CriticalCompletionFraction);
        Assert.Equal(1d, summary.HighCompletionFraction);
        Assert.DoesNotContain(
            manifest.Features,
            static feature => (feature.Priority is ParityPriority.Critical or ParityPriority.High)
                && (feature.Status is ParityStatus.Partial or ParityStatus.Missing));
    }

    [Fact]
    public async Task CompletedParityEvidenceResolvesInsideRepository()
    {
        ParityManifest manifest = await ParityManifestLoader.LoadAsync(ManifestPath);

        Assert.Empty(ParityRepositoryValidator.ValidateEvidence(manifest, RepositoryRoot));
    }

    [Fact]
    public void LegacyApplicationSourceIsAbsent()
        => Assert.Empty(ParityRepositoryValidator.ValidateLegacySourceRemoval(RepositoryRoot));

    [Fact]
    public void ModernSolutionContainsOnlyApprovedProjects()
        => Assert.Empty(ParityRepositoryValidator.ValidateModernSolution(RepositoryRoot));

    [Fact]
    public void LinuxAndWindowsQualificationWorkflowsArePresent()
    {
        string workflow = File.ReadAllText(Path.Combine(
            RepositoryRoot,
            ".github",
            "workflows",
            "modern-ci.yml"));

        Assert.Contains("ubuntu-latest", workflow, StringComparison.Ordinal);
        Assert.Contains("windows-latest", workflow, StringComparison.Ordinal);
        Assert.Contains("final-gate", workflow, StringComparison.Ordinal);
        Assert.Contains("smoke-package.sh", workflow, StringComparison.Ordinal);
        Assert.Contains("smoke-package.ps1", workflow, StringComparison.Ordinal);
        Assert.DoesNotContain("XDM_CoreFx.sln", workflow, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public void ModernPackagingQualificationScriptsExist()
    {
        string[] required =
        [
            "app/XDM/eng/final-gate.sh",
            "app/XDM/eng/final-gate.ps1",
            "app/XDM/eng/qualify-prerelease.sh",
            "app/XDM/eng/qualify-prerelease.ps1",
            "app/XDM/eng/smoke-package.sh",
            "app/XDM/eng/smoke-package.ps1"
        ];

        Assert.All(required, relative => Assert.True(
            File.Exists(Path.Combine(RepositoryRoot, relative)),
            $"Required qualification script is missing: {relative}"));
    }
}
