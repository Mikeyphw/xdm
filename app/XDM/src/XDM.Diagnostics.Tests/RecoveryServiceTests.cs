using XDM.Diagnostics;

namespace XDM.Diagnostics.Tests;

public sealed class RecoveryServiceTests
{
    [Fact]
    public void ExistingSessionMarkerIsDetectedAndCleanShutdownIsPersistedAfterFlush()
    {
        string directory = Path.Combine(Path.GetTempPath(), $"xdm-recovery-{Guid.NewGuid():N}");
        Directory.CreateDirectory(directory);
        try
        {
            File.WriteAllText(Path.Combine(directory, "session.running.json"), "{}");
            RecoveryService service = new(directory);
            service.Initialize(new StartupOptions(SafeMode: true, ResetWindowState: true));

            Assert.True(service.PreviousSessionWasUnclean);
            Assert.True(service.SafeMode);
            Assert.False(string.IsNullOrWhiteSpace(service.SessionId));
            string[] activeIds = ["download-b", "download-a", "download-a"];
            service.BeginShutdown(activeIds);
            string[] failedIds = [];
            service.RecordCheckpointFlush(true, 2, 2, failedIds);
            service.MarkCleanShutdown();

            Assert.False(File.Exists(Path.Combine(directory, "session.running.json")));
            Assert.True(File.Exists(Path.Combine(directory, "session.last.json")));
            string payload = File.ReadAllText(Path.Combine(directory, "session.last.json"));
            Assert.Contains("download-a", payload, StringComparison.Ordinal);
            Assert.Contains("cleanShutdownAt", payload, StringComparison.Ordinal);
        }
        finally
        {
            Directory.Delete(directory, recursive: true);
        }
    }

    [Fact]
    public void CleanShutdownCannotBeRecordedBeforeCheckpointFlush()
    {
        string directory = Path.Combine(Path.GetTempPath(), $"xdm-recovery-{Guid.NewGuid():N}");
        Directory.CreateDirectory(directory);
        try
        {
            RecoveryService service = new(directory);
            service.Initialize(StartupOptions.Default);
            string[] activeIds = ["active"];
            service.BeginShutdown(activeIds);

            Assert.Throws<InvalidOperationException>(service.MarkCleanShutdown);
            Assert.True(File.Exists(Path.Combine(directory, "session.running.json")));
        }
        finally
        {
            Directory.Delete(directory, recursive: true);
        }
    }

    [Fact]
    public void FailedCheckpointFlushLeavesDurableUncleanSessionDetails()
    {
        string directory = Path.Combine(Path.GetTempPath(), $"xdm-recovery-{Guid.NewGuid():N}");
        Directory.CreateDirectory(directory);
        try
        {
            RecoveryService first = new(directory);
            first.Initialize(StartupOptions.Default);
            string[] activeIds = ["download-1"];
            first.BeginShutdown(activeIds);
            first.RecordCheckpointFlush(false, 1, 0, activeIds);

            RecoveryService second = new(directory);
            second.Initialize(StartupOptions.Default);

            Assert.True(second.PreviousSessionWasUnclean);
            Assert.NotNull(second.PreviousSession);
            Assert.Equal("download-1", Assert.Single(second.PreviousSession!.ActiveDownloadIds));
            Assert.Equal(false, second.PreviousSession.CheckpointFlushSucceeded);
            Assert.Equal("download-1", Assert.Single(second.PreviousSession.FailedCheckpointDownloadIds));
        }
        finally
        {
            Directory.Delete(directory, recursive: true);
        }
    }
}
