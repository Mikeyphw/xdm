namespace XDM.Parity;

public static class ParityManifestValidator
{
    public static string[] Validate(ParityManifest manifest)
    {
        ArgumentNullException.ThrowIfNull(manifest);
        List<string> issues = [];
        if (manifest.SchemaVersion != 1)
        {
            issues.Add($"Unsupported parity schema version {manifest.SchemaVersion}.");
        }

        if (string.IsNullOrWhiteSpace(manifest.Baseline))
        {
            issues.Add("The parity baseline must be named.");
        }

        HashSet<string> ids = new(StringComparer.Ordinal);
        foreach (ParityFeature feature in manifest.Features)
        {
            if (string.IsNullOrWhiteSpace(feature.Id))
            {
                issues.Add("A parity feature has an empty id.");
                continue;
            }

            if (!ids.Add(feature.Id))
            {
                issues.Add($"Duplicate parity feature id: {feature.Id}.");
            }

            if (string.IsNullOrWhiteSpace(feature.Area) || string.IsNullOrWhiteSpace(feature.Name))
            {
                issues.Add($"{feature.Id}: area and name are required.");
            }

            if (feature.LegacySources.Count == 0)
            {
                issues.Add($"{feature.Id}: at least one legacy source or public contract reference is required.");
            }

            if (string.IsNullOrWhiteSpace(feature.TargetOverlay))
            {
                issues.Add($"{feature.Id}: targetOverlay is required.");
            }

            if (feature.Status == ParityStatus.Complete)
            {
                if (feature.ImplementationPaths.Count == 0)
                {
                    issues.Add($"{feature.Id}: complete features require implementation paths.");
                }

                if (feature.AutomatedTests.Count == 0)
                {
                    issues.Add($"{feature.Id}: complete features require automated tests.");
                }
            }
        }

        return [.. issues];
    }

    public static ParitySummary Summarize(ParityManifest manifest)
    {
        ArgumentNullException.ThrowIfNull(manifest);
        Dictionary<ParityStatus, int> counts = Enum.GetValues<ParityStatus>()
            .ToDictionary(status => status, status => manifest.Features.Count(feature => feature.Status == status));
        int criticalTotal = manifest.Features.Count(feature => feature.Priority == ParityPriority.Critical);
        int criticalComplete = manifest.Features.Count(feature =>
            feature.Priority == ParityPriority.Critical
            && feature.Status is ParityStatus.Complete or ParityStatus.IntentionallyReplaced or ParityStatus.NotApplicable);
        return new ParitySummary(manifest.Features.Count, counts, criticalComplete, criticalTotal);
    }
}

public sealed record ParitySummary(
    int Total,
    IReadOnlyDictionary<ParityStatus, int> Counts,
    int CriticalComplete,
    int CriticalTotal)
{
    public double CriticalCompletionFraction
        => CriticalTotal == 0 ? 1d : (double)CriticalComplete / CriticalTotal;
}
