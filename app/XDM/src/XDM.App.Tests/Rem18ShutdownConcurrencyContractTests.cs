namespace XDM.App.Tests;

public sealed class Rem18ShutdownConcurrencyContractTests
{
    [Fact]
    public void ShutdownCoordinatorPreparesGoHostTransfersBeforeRecoverySnapshotAndCleanMarker()
    {
        string root = FindRepositoryRoot(AppContext.BaseDirectory);
        string source = File.ReadAllText(Path.Combine(root, "app", "XDM", "src", "XDM.App", "Services", "ShutdownCoordinator.cs"));

        int broker = source.IndexOf(
            "services.GetRequiredService<DesktopGoDownloadExecutionBroker>()",
            StringComparison.Ordinal);
        int prepare = source.IndexOf(
            "downloads.PrepareForShutdownAsync",
            StringComparison.Ordinal);
        int begin = source.IndexOf(
            "recovery.BeginShutdown(report.ActiveDownloadIds)",
            StringComparison.Ordinal);
        int dispose = source.IndexOf(
            "services.Dispose()",
            StringComparison.Ordinal);
        int clean = source.IndexOf(
            "recovery.MarkCleanShutdown()",
            StringComparison.Ordinal);

        Assert.True(broker >= 0, "Shutdown must use the narrow XGO desktop execution broker.");
        Assert.True(prepare > broker, "Physical transfer preparation must use the Go-authority host broker.");
        Assert.True(begin > prepare, "Recovery must record the active IDs produced by transfer preparation.");
        Assert.True(dispose > begin, "Service and Go-host teardown belongs after recovery state is captured.");
        Assert.True(clean > dispose, "Clean marker must be written only after service teardown.");
    }

    [Fact]
    public void DownloadManagerRetainsBoundedShutdownAdmissionBarrier()
    {
        string root = FindRepositoryRoot(AppContext.BaseDirectory);
        string source = File.ReadAllText(Path.Combine(root, "app", "XDM", "src", "XDM.DownloadEngine", "DownloadManager.cs"));

        Assert.Contains("ShutdownBudget = TimeSpan.FromSeconds(15)", source, StringComparison.Ordinal);
        Assert.Contains("_admissionClosed", source, StringComparison.Ordinal);
        Assert.Contains("ThrowIfAdmissionClosed()", source, StringComparison.Ordinal);
        Assert.Contains("GetRemainingShutdownBudget()", source, StringComparison.Ordinal);
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
