using XDM.Diagnostics;

namespace XDM.Diagnostics.Tests;

public sealed class RecoveryServiceTests
{
    [Fact]
    public void ExistingSessionMarkerIsDetectedAndCleaned()
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
            service.MarkCleanShutdown();
            Assert.False(File.Exists(Path.Combine(directory, "session.running.json")));
        }
        finally
        {
            Directory.Delete(directory, recursive: true);
        }
    }
}
