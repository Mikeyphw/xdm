using XDM.Platform;

namespace XDM.Core.Tests;

public sealed class DesktopNotificationServiceTests
{
    [Fact]
    public void CreatesCommandForCurrentDesktopPlatform()
    {
        NotificationCommand? command = NotificationCommandFactory.Create("Completed", "file.zip");
        if (OperatingSystem.IsLinux() || OperatingSystem.IsMacOS() || OperatingSystem.IsWindows())
        {
            Assert.NotNull(command);
            Assert.NotEmpty(command.Arguments);
        }
    }
}
