namespace XDM.App.Tests;

public sealed class Rem18ShutdownConcurrencyContractTests
{
    [Fact]
    public void ShutdownCoordinatorFreezesAdmissionBeforeRecoverySnapshotAndCleanMarker()
    {
        string root = FindRepositoryRoot(AppContext.BaseDirectory);
        string source = File.ReadAllText(Path.Combine(root, "app", "XDM", "src", "XDM.App", "Services", "ShutdownCoordinator.cs"));

        int freeze = source.IndexOf("downloads.FreezeAdmission()", StringComparison.Ordinal);
        int begin = source.IndexOf("recovery.BeginShutdown(activeIds)", StringComparison.Ordinal);
        int prepare = source.IndexOf("downloads.PrepareForShutdownAsync", StringComparison.Ordinal);
        int dispose = source.IndexOf("services.Dispose()", StringComparison.Ordinal);
        int clean = source.IndexOf("recovery.MarkCleanShutdown()", StringComparison.Ordinal);

        Assert.True(freeze >= 0, "Shutdown must freeze new download admission.");
        Assert.True(begin > freeze, "Recovery snapshot must happen after admission freeze.");
        Assert.True(prepare > begin, "Checkpoint preparation must happen after recovery has active IDs.");
        Assert.True(dispose > prepare, "Service disposal belongs after checkpoint preparation.");
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
