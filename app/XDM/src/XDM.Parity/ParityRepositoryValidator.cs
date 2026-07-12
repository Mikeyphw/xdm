using System.Text.RegularExpressions;

namespace XDM.Parity;

public static partial class ParityRepositoryValidator
{
    private static readonly string[] ForbiddenLegacyPaths =
    [
        "app/XDM/XDM.Wpf.UI",
        "app/XDM/XDM.Gtk.UI",
        "app/XDM/XDM.WinForms.IntegrationUI",
        "app/XDM/MsixPackaging",
        "app/XDM/XDM.Msix.AutoLaunch",
        "app/XDM/XDM.App.Host",
        "app/XDM/XDM.Core",
        "app/XDM/XDM.Messaging",
        "app/XDM/XDM.Compatibility",
        "app/XDM/XDM.Tests",
        "app/XDM/XDM_Tests",
        "app/XDM/MockServer",
        "app/XDM/XDM_CoreFx.sln"
    ];

    private static readonly string[] AllowedModernProjects =
    [
        "XDM.App",
        "XDM.Core",
        "XDM.Platform",
        "XDM.Persistence",
        "XDM.DownloadEngine",
        "XDM.Core.Tests",
        "XDM.DownloadEngine.Tests",
        "XDM.BrowserIntegration",
        "XDM.Media",
        "XDM.BrowserMedia.Tests",
        "XDM.Diagnostics",
        "XDM.Diagnostics.Tests",
        "XDM.NativeHost",
        "XDM.Parity",
        "XDM.Parity.Tests"
    ];

    public static string[] ValidateEvidence(ParityManifest manifest, string repositoryRoot)
    {
        ArgumentNullException.ThrowIfNull(manifest);
        ArgumentException.ThrowIfNullOrWhiteSpace(repositoryRoot);

        string root = Path.GetFullPath(repositoryRoot);
        List<string> issues = [];
        string[] testFiles = Directory.Exists(Path.Combine(root, "app", "XDM", "src"))
            ? Directory.GetFiles(Path.Combine(root, "app", "XDM", "src"), "*.cs", SearchOption.AllDirectories)
                .Where(static path => path.Contains(".Tests", StringComparison.Ordinal))
                .ToArray()
            : [];

        foreach (ParityFeature feature in manifest.Features.Where(RequiresEvidence))
        {
            foreach (string relativePath in feature.ImplementationPaths)
            {
                if (!TryResolveRepositoryPath(root, relativePath, out string? fullPath)
                    || (!File.Exists(fullPath) && !Directory.Exists(fullPath)))
                {
                    issues.Add($"{feature.Id}: implementation path does not exist: {relativePath}.");
                }
            }

            foreach (string testReference in feature.AutomatedTests)
            {
                if (!TestReferenceExists(testReference, testFiles))
                {
                    issues.Add($"{feature.Id}: automated test reference does not resolve: {testReference}.");
                }
            }
        }

        return [.. issues];
    }

    public static string[] ValidateLegacySourceRemoval(string repositoryRoot)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(repositoryRoot);
        string root = Path.GetFullPath(repositoryRoot);
        return ForbiddenLegacyPaths
            .Where(path => File.Exists(Path.Combine(root, path)) || Directory.Exists(Path.Combine(root, path)))
            .Select(path => $"Legacy application source is still present: {path}.")
            .ToArray();
    }

    public static string[] ValidateModernSolution(string repositoryRoot)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(repositoryRoot);
        string solutionPath = Path.Combine(
            Path.GetFullPath(repositoryRoot),
            "app",
            "XDM",
            "XDM.Modern.sln");
        if (!File.Exists(solutionPath))
        {
            return ["The active modern solution is missing."];
        }

        HashSet<string> actual = File.ReadLines(solutionPath)
            .Select(static line => ProjectNameRegex().Match(line))
            .Where(static match => match.Success)
            .Select(static match => match.Groups[1].Value)
            .ToHashSet(StringComparer.Ordinal);
        HashSet<string> expected = AllowedModernProjects.ToHashSet(StringComparer.Ordinal);

        List<string> issues = [];
        foreach (string unexpected in actual.Except(expected, StringComparer.Ordinal).Order(StringComparer.Ordinal))
        {
            issues.Add($"Inactive or unknown project is present in XDM.Modern.sln: {unexpected}.");
        }

        foreach (string missing in expected.Except(actual, StringComparer.Ordinal).Order(StringComparer.Ordinal))
        {
            issues.Add($"Expected modern project is missing from XDM.Modern.sln: {missing}.");
        }

        return [.. issues];
    }

    public static string FindRepositoryRoot(string startPath)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(startPath);
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

        throw new DirectoryNotFoundException("The XDM repository root could not be located.");
    }

    private static bool RequiresEvidence(ParityFeature feature)
        => feature.Status is ParityStatus.Complete or ParityStatus.IntentionallyReplaced;

    private static bool TryResolveRepositoryPath(
        string repositoryRoot,
        string relativePath,
        out string? fullPath)
    {
        fullPath = null;
        if (string.IsNullOrWhiteSpace(relativePath) || Path.IsPathRooted(relativePath))
        {
            return false;
        }

        string candidate = Path.GetFullPath(Path.Combine(repositoryRoot, relativePath));
        string prefix = repositoryRoot.EndsWith(Path.DirectorySeparatorChar)
            ? repositoryRoot
            : repositoryRoot + Path.DirectorySeparatorChar;
        if (!candidate.StartsWith(prefix, StringComparison.Ordinal))
        {
            return false;
        }

        fullPath = candidate;
        return true;
    }

    private static bool TestReferenceExists(string reference, string[] testFiles)
    {
        if (string.IsNullOrWhiteSpace(reference))
        {
            return false;
        }

        string[] segments = reference.Split('.', StringSplitOptions.RemoveEmptyEntries);
        if (segments.Length < 4)
        {
            return false;
        }

        string className = segments[^2];
        string memberName = segments[^1];
        bool memberReference = memberName.EndsWith("Tests", StringComparison.Ordinal) is false;
        if (!memberReference)
        {
            className = memberName;
        }

        foreach (string testFile in testFiles)
        {
            string source = File.ReadAllText(testFile);
            if (!Regex.IsMatch(source, $@"\bclass\s+{Regex.Escape(className)}\b", RegexOptions.CultureInvariant))
            {
                continue;
            }

            if (!memberReference
                || Regex.IsMatch(source, $@"\b{Regex.Escape(memberName)}\s*\(", RegexOptions.CultureInvariant))
            {
                return true;
            }
        }

        return false;
    }

    [GeneratedRegex("^Project\\(.*\\) = \\\"([^\\\"]+)\\\"", RegexOptions.CultureInvariant)]
    private static partial Regex ProjectNameRegex();
}
