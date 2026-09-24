using System.Diagnostics;

namespace XDM.Core.Tests;

public sealed class Rem18ReleaseMatrixContractTests
{
    [Fact]
    public async Task OsProcessPipeDrainAndExitObservationAreCoveredByFinalGate()
    {
        using CancellationTokenSource timeout = new(TimeSpan.FromSeconds(10));
        ProcessStartInfo startInfo = OperatingSystem.IsWindows()
            ? new ProcessStartInfo("cmd.exe", "/c echo rem18-process")
            : new ProcessStartInfo("/bin/sh", "-c \"printf rem18-process\"");
        startInfo.RedirectStandardOutput = true;
        startInfo.RedirectStandardError = true;
        startInfo.UseShellExecute = false;

        using Process process = Process.Start(startInfo)
            ?? throw new InvalidOperationException("Unable to start REM18 process semantics probe.");
        Task<string> outputTask = process.StandardOutput.ReadToEndAsync(timeout.Token);
        Task<string> errorTask = process.StandardError.ReadToEndAsync(timeout.Token);

        await process.WaitForExitAsync(timeout.Token);
        string output = await outputTask;
        string error = await errorTask;

        Assert.True(process.ExitCode == 0, $"REM18 process semantics probe failed with exit {process.ExitCode}: {error}");
        Assert.Equal("rem18-process", output.Trim());
        Assert.True(string.IsNullOrWhiteSpace(error), error);
    }

    [Fact]
    public void ReleaseWorkflowsDeclareFullPlatformRidMatrixAndSigningTrust()
    {
        string root = FindRepositoryRoot(AppContext.BaseDirectory);
        string workflow = File.ReadAllText(Path.Combine(root, ".github", "workflows", "modern-release.yml"));
        string finalSeal = File.ReadAllText(Path.Combine(root, "app", "XDM", "eng", "rem18-final-release-seal.sh"));
        string powerShellSeal = File.ReadAllText(Path.Combine(root, "app", "XDM", "eng", "rem18-final-release-seal.ps1"));
        string combined = workflow + "\n" + finalSeal + "\n" + powerShellSeal;

        Assert.Contains("linux-x64", combined, StringComparison.Ordinal);
        Assert.Contains("linux-arm64", combined, StringComparison.Ordinal);
        Assert.Contains("win-x64", combined, StringComparison.Ordinal);
        Assert.Contains("win-arm64", combined, StringComparison.Ordinal);
        Assert.Contains("WINDOWS_SIGNING_CERTIFICATE", workflow, StringComparison.Ordinal);
        Assert.Contains("verify-windows-signatures.ps1", workflow, StringComparison.Ordinal);
        Assert.Contains("actions/attest@v4", workflow, StringComparison.Ordinal);
        Assert.Contains("releaseCommitSha", File.ReadAllText(Path.Combine(root, "app", "XDM", "eng", "generate-release-metadata.py")), StringComparison.Ordinal);
    }

    [Fact]
    public void DevtoolDesktopTargetUsesExoVisibleRem18PackageWorkflowAndNoScalarPythonTarget()
    {
        string root = FindRepositoryRoot(AppContext.BaseDirectory);
        string config = File.ReadAllText(Path.Combine(root, ".devtool.toml"));

        Assert.Contains("[targets.xdm_modern]", config, StringComparison.Ordinal);
        Assert.Contains("type = \"dotnet\"", config, StringComparison.Ordinal);
        Assert.Contains("[targets.xdm_modern.workflows]", config, StringComparison.Ordinal);
        Assert.Contains("\"job:xdm-rem18-package-matrix\"", config, StringComparison.Ordinal);
        Assert.Contains("\"job:xdm-rem18-linux-x64-smoke\"", config, StringComparison.Ordinal);
        Assert.Contains("\"job:xdm-rem18-linux-arm64-smoke\"", config, StringComparison.Ordinal);
        Assert.Contains("\"job:xdm-rem18-finalize\"", config, StringComparison.Ordinal);
        Assert.Contains("--devtool-package-matrix-only", config, StringComparison.Ordinal);
        Assert.Contains("--devtool-finalize-only", config, StringComparison.Ordinal);
        Assert.DoesNotContain("package_command =", config, StringComparison.Ordinal);
        Assert.DoesNotContain("python = \"\"", config, StringComparison.Ordinal);
        Assert.DoesNotContain("run_module = \"\"", config, StringComparison.Ordinal);
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
