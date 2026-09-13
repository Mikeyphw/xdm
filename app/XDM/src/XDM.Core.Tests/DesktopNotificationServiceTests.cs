using XDM.Core.Abstractions;
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
            Assert.False(string.IsNullOrWhiteSpace(command.Channel));
        }
    }

    [Fact]
    public void WindowsCommandDeclaresAumidAndShortcutRegistration()
    {
        NotificationCommand? command = NotificationCommandFactory.Create(
            "Completed",
            "file.zip",
            DesktopNotificationPlatform.Windows,
            "C:\\Program Files\\XDM\\XDM.exe");

        Assert.NotNull(command);
        Assert.Equal("powershell.exe", command.FileName);
        Assert.Equal("windows-toast-aumid", command.Channel);
        string script = string.Join("\n", command.Arguments);
        Assert.Contains(DesktopNotificationCommandFactory.WindowsAppUserModelId, script, StringComparison.Ordinal);
        Assert.Contains("AppUserModelID", script, StringComparison.Ordinal);
        Assert.Contains("CreateToastNotifier($aumid)", script, StringComparison.Ordinal);
        Assert.Contains("Xtreme Download Manager.lnk", script, StringComparison.Ordinal);
    }

    [Fact]
    public void LinuxCommandUsesNotifySendWithAppIdentity()
    {
        NotificationCommand? command = NotificationCommandFactory.Create(
            "Completed",
            "file.zip",
            DesktopNotificationPlatform.Linux);

        Assert.NotNull(command);
        Assert.Equal("notify-send", command.FileName);
        Assert.Equal("linux-notify-send", command.Channel);
        Assert.Contains("--app-name=XDM", command.Arguments);
        Assert.Contains("--expire-time=7000", command.Arguments);
    }

    [Fact]
    public async Task DeliveryHasDeadlineAndReportsTimeout()
    {
        HangingNotificationCommandExecutor executor = new();
        DesktopNotificationService service = new(
            new FixedNotificationCommandFactory(new NotificationCommand("notify-send", ["title", "message"], "test")),
            executor,
            TimeSpan.FromMilliseconds(20));

        DesktopNotificationDeliveryResult result = await service.ShowAsync("Title", "Message");

        Assert.False(result.Delivered);
        Assert.True(result.Supported);
        Assert.True(result.TimedOut);
        Assert.Equal("test", result.Channel);
        Assert.True(executor.WasCancelled);
    }

    [Fact]
    public async Task DeliveryFailureIsReportedInsteadOfPretendingShown()
    {
        DesktopNotificationService service = new(
            new FixedNotificationCommandFactory(new NotificationCommand("notify-send", ["title", "message"], "test")),
            new FixedNotificationCommandExecutor(new NotificationCommandExecutionResult(1, string.Empty, "backend missing")),
            TimeSpan.FromSeconds(1));

        DesktopNotificationDeliveryResult result = await service.ShowAsync("Title", "Message");

        Assert.False(result.Delivered);
        Assert.True(result.Supported);
        Assert.False(result.TimedOut);
        Assert.Equal("backend missing", result.Failure);
    }

    [Fact]
    public async Task UnsupportedPlatformReturnsExplicitUnsupportedState()
    {
        DesktopNotificationService service = new(
            new FixedNotificationCommandFactory(null),
            new FixedNotificationCommandExecutor(new NotificationCommandExecutionResult(0, string.Empty, string.Empty)),
            TimeSpan.FromSeconds(1));

        DesktopNotificationDeliveryResult result = await service.ShowAsync("Title", "Message");

        Assert.False(result.Delivered);
        Assert.False(result.Supported);
        Assert.Equal("unsupported", result.Channel);
    }

    private sealed class FixedNotificationCommandFactory(NotificationCommand? command) : IDesktopNotificationCommandFactory
    {
        public NotificationCommand? Create(string title, string message) => command;
    }

    private sealed class FixedNotificationCommandExecutor(NotificationCommandExecutionResult result) : INotificationCommandExecutor
    {
        public Task<NotificationCommandExecutionResult> ExecuteAsync(
            NotificationCommand command,
            CancellationToken cancellationToken = default)
            => Task.FromResult(result);
    }

    private sealed class HangingNotificationCommandExecutor : INotificationCommandExecutor
    {
        public bool WasCancelled { get; private set; }

        public async Task<NotificationCommandExecutionResult> ExecuteAsync(
            NotificationCommand command,
            CancellationToken cancellationToken = default)
        {
            try
            {
                await Task.Delay(Timeout.InfiniteTimeSpan, cancellationToken);
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                WasCancelled = true;
                throw;
            }

            return new NotificationCommandExecutionResult(0, string.Empty, string.Empty);
        }
    }
}
