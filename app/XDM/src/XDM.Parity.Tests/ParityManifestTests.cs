using XDM.Parity;

namespace XDM.Parity.Tests;

public sealed class ParityManifestTests
{
    private static string ManifestPath => Path.Combine(AppContext.BaseDirectory, "features.json");

    [Fact]
    public async Task ManifestIsValidAndFeatureIdsAreUnique()
    {
        ParityManifest manifest = await ParityManifestLoader.LoadAsync(ManifestPath);
        IReadOnlyList<string> issues = ParityManifestValidator.Validate(manifest);

        Assert.Empty(issues);
        Assert.NotEmpty(manifest.Features);
        Assert.Equal(
            manifest.Features.Count,
            manifest.Features.Select(static feature => feature.Id).Distinct(StringComparer.Ordinal).Count());
    }

    [Fact]
    public async Task QualifiedFeaturesAreBackedByImplementationAndTests()
    {
        ParityManifest manifest = await ParityManifestLoader.LoadAsync(ManifestPath);
        ParityFeature[] qualified = manifest.Features
            .Where(static feature => feature.Status is ParityStatus.Complete or ParityStatus.IntentionallyReplaced)
            .ToArray();

        Assert.NotEmpty(qualified);
        Assert.All(qualified, static feature => Assert.NotEmpty(feature.ImplementationPaths));
        Assert.All(qualified, static feature => Assert.NotEmpty(feature.AutomatedTests));
    }

    [Fact]
    public async Task CriticalParityHasNoUnownedWork()
    {
        ParityManifest manifest = await ParityManifestLoader.LoadAsync(ManifestPath);
        ParityFeature[] critical = manifest.Features
            .Where(static feature => feature.Priority == ParityPriority.Critical)
            .ToArray();

        Assert.NotEmpty(critical);
        Assert.All(critical, static feature => Assert.False(string.IsNullOrWhiteSpace(feature.TargetOverlay)));
    }
}
