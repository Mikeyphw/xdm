namespace XDM.Core.Tests;

public sealed class XgoGate12DesktopQualificationContractTests
{
    [Fact]
    public void QueueSchedulerCompatibilityContractIsCoreOwnedAndDonorImplementationStaysQuarantined()
    {
        string root = FindRepositoryRoot(AppContext.BaseDirectory);

        string coreContract = File.ReadAllText(Path.Combine(
            root, "app", "XDM", "src", "XDM.Core", "Scheduling", "IQueueSchedulerRuntime.cs"));
        string project = File.ReadAllText(Path.Combine(
            root, "app", "XDM", "src", "XDM.DownloadEngine", "XDM.DownloadEngine.csproj"));
        string compatibility = File.ReadAllText(Path.Combine(
            root, "app", "XDM", "src", "XDM.Platform", "DesktopGoCompatibilityServices.cs"));
        string app = File.ReadAllText(Path.Combine(
            root, "app", "XDM", "src", "XDM.App", "App.axaml.cs"));
        string viewModel = File.ReadAllText(Path.Combine(
            root, "app", "XDM", "src", "XDM.App", "ViewModels", "MainWindowViewModel.cs"));
        string hostTest = File.ReadAllText(Path.Combine(
            root, "app", "XDM", "src", "XDM.Core.Tests", "DesktopGoHostTests.cs"));

        Assert.Contains("namespace XDM.Core.Scheduling;", coreContract, StringComparison.Ordinal);
        Assert.Contains("public interface IQueueSchedulerRuntime", coreContract, StringComparison.Ordinal);

        Assert.Contains("<Compile Remove=\"Queues/QueueSchedulerRuntime.cs\" />", project, StringComparison.Ordinal);
        Assert.Contains("<Compile Remove=\"Queues/QueueDependencyEvaluator.cs\" />", project, StringComparison.Ordinal);

        Assert.DoesNotContain("using XDM.DownloadEngine.Queues;", compatibility, StringComparison.Ordinal);
        Assert.DoesNotContain("using XDM.DownloadEngine.Queues;", app, StringComparison.Ordinal);
        Assert.DoesNotContain("using XDM.DownloadEngine.Queues;", viewModel, StringComparison.Ordinal);

        Assert.Contains("DesktopGoQueueSchedulerRuntime : IQueueSchedulerRuntime", compatibility, StringComparison.Ordinal);
        Assert.Contains("AddSingleton<IQueueSchedulerRuntime, DesktopGoQueueSchedulerRuntime>()", app, StringComparison.Ordinal);

        Assert.Contains("ExpectedSchemaVersion = 1", hostTest, StringComparison.Ordinal);
        Assert.DoesNotContain("ExpectedSchemaVersion = 0", hostTest, StringComparison.Ordinal);
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
