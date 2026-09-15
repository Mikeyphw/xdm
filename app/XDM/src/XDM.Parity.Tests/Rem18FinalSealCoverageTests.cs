using System.Text.Json;

namespace XDM.Parity.Tests;

public sealed class Rem18FinalSealCoverageTests
{
    private static string RepositoryRoot => XDM.Parity.ParityRepositoryValidator.FindRepositoryRoot(AppContext.BaseDirectory);

    [Fact]
    public void ClosureLedgerContainsAllFrozenFindingsAndSeverityTotals()
    {
        using JsonDocument document = JsonDocument.Parse(File.ReadAllText(Path.Combine(
            RepositoryRoot,
            "docs",
            "remediation",
            "XDM_DESKTOP_FINAL_REMEDIATION_LEDGER.json")));
        JsonElement findings = document.RootElement.GetProperty("findings");

        Assert.Equal(258, findings.GetArrayLength());
        Assert.Equal(67, findings.EnumerateArray().Count(static item => item.GetProperty("severity").GetString() == "HIGH"));
        Assert.Equal(153, findings.EnumerateArray().Count(static item => item.GetProperty("severity").GetString() == "MEDIUM"));
        Assert.Equal(38, findings.EnumerateArray().Count(static item => item.GetProperty("severity").GetString() == "LOW"));
        Assert.Equal(
            258,
            findings.EnumerateArray()
                .Select(static item => item.GetProperty("id").GetString() ?? string.Empty)
                .Distinct(StringComparer.Ordinal)
                .Count());
    }

    [Fact]
    public void EveryFrozenFindingIsClosedWithRegressionEvidence()
    {
        using JsonDocument document = JsonDocument.Parse(File.ReadAllText(Path.Combine(
            RepositoryRoot,
            "docs",
            "remediation",
            "XDM_DESKTOP_FINAL_REMEDIATION_LEDGER.json")));

        foreach (JsonElement finding in document.RootElement.GetProperty("findings").EnumerateArray())
        {
            string id = finding.GetProperty("id").GetString() ?? string.Empty;
            string status = finding.GetProperty("status").GetString() ?? string.Empty;
            string regressionStatus = finding.GetProperty("regressionStatus").GetString() ?? string.Empty;
            Assert.Equal("Closed", status);
            Assert.Contains("Regression-covered", regressionStatus, StringComparison.Ordinal);
            Assert.True(finding.GetProperty("evidence").GetArrayLength() > 0, $"{id} must have evidence.");
        }
    }

    [Fact]
    public void Rem18OwnsOnlyTheFinalCrossBoundaryFindings()
    {
        using JsonDocument document = JsonDocument.Parse(File.ReadAllText(Path.Combine(
            RepositoryRoot,
            "docs",
            "remediation",
            "XDM_DESKTOP_FINAL_REMEDIATION_LEDGER.json")));
        string[] rem18Ids = document.RootElement.GetProperty("findings")
            .EnumerateArray()
            .Where(static item => item.GetProperty("overlay").GetString() == "REM18")
            .Select(static item => item.GetProperty("id").GetString() ?? string.Empty)
            .Order(StringComparer.Ordinal)
            .ToArray();

        Assert.Equal(["S13-12", "S14-10", "S16-07"], rem18Ids);
    }

    [Fact]
    public void Rem18ScriptsArePartOfFinalGateEvidence()
    {
        string[] required =
        [
            "app/XDM/eng/rem18-final-release-seal.sh",
            "app/XDM/eng/rem18-final-release-seal.ps1",
            "app/XDM/eng/rem18-ledger-audit.py",
            "app/XDM/eng/rem18-release-matrix-audit.py",
            "app/XDM/eng/rem18-fault-injection-matrix.json"
        ];

        Assert.All(required, relative => Assert.True(
            File.Exists(Path.Combine(RepositoryRoot, relative)),
            $"REM18 final-seal evidence file is missing: {relative}"));
    }
}
