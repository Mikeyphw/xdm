using System.Text.Json;

namespace XDM.BrowserMedia.Tests;

public sealed class Rem18MediaBrowserFaultMatrixTests
{
    [Fact]
    public void FaultMatrixCoversBrowserMediaFfmpegAria2AndUpdaterBoundaries()
    {
        string root = FindRepositoryRoot(AppContext.BaseDirectory);
        using JsonDocument document = JsonDocument.Parse(File.ReadAllText(Path.Combine(
            root,
            "app",
            "XDM",
            "eng",
            "rem18-fault-injection-matrix.json")));
        string[] ids = document.RootElement.GetProperty("scenarios")
            .EnumerateArray()
            .Select(static item => item.GetProperty("id").GetString() ?? string.Empty)
            .Order(StringComparer.Ordinal)
            .ToArray();

        Assert.Contains("browser-native-host-stalled-client", ids);
        Assert.Contains("shutdown-during-browser-admission", ids);
        Assert.Contains("aria2-process-death-resume", ids);
        Assert.Contains("ffmpeg-output-pressure-timeout-cancel", ids);
        Assert.Contains("updater-swap-power-loss-rollback-window", ids);
        Assert.Contains("hls-dash-workspace-collision-subtitle-publication", ids);
        Assert.Contains("network-proxy-mirror-origin-change", ids);
        Assert.Contains("resume-recovery-corrupt-journal-disk-pressure", ids);
    }

    [Fact]
    public void MediaWorkspaceScenarioRequiresSubtitleAndCheckpointPublicationProof()
    {
        string root = FindRepositoryRoot(AppContext.BaseDirectory);
        string matrix = File.ReadAllText(Path.Combine(root, "app", "XDM", "eng", "rem18-fault-injection-matrix.json"));

        Assert.Contains("workspace locks prevent cross-job collision", matrix, StringComparison.Ordinal);
        Assert.Contains("checkpoint identities own fragment order", matrix, StringComparison.Ordinal);
        Assert.Contains("subtitle files retain extension and publish only after media success", matrix, StringComparison.Ordinal);
        Assert.Contains("DRM and redirect-base metadata remain visible", matrix, StringComparison.Ordinal);
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
