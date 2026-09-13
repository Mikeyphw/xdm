using System.Diagnostics;
using XDM.Core.Abstractions;
using XDM.Core.Scheduling;
using XDM.Platform;

namespace XDM.Core.Tests;

public sealed class PlatformCommandSafetyTests
{
    [Fact]
    public void CatalogNeverUsesUnsafeLinuxTerminateUserLogout()
    {
        PlatformPowerCommandCatalog catalog = PlatformPowerCommandCatalog.Discover();
        if (catalog.TryGet(ScheduleCompletionActionKind.LogOut, out PlatformPowerCommand? command) && command is not null)
        {
            Assert.DoesNotContain("terminate-user", command.Arguments);
        }
    }

    [Fact]
    public async Task CompletionActionReportsCatalogUnsupportedReason()
    {
        PlatformCompletionActionService service = new(
            new RecordingRunner(),
            new RecordingLifetime(),
            new PlatformPowerCommandCatalog(
                new Dictionary<ScheduleCompletionActionKind, PlatformPowerCommand>(),
                new Dictionary<ScheduleCompletionActionKind, string>
                {
                    [ScheduleCompletionActionKind.Sleep] = "sleep unavailable in this session"
                }));

        CompletionActionResult result = await service.ExecuteAsync(
            new ScheduleCompletionAction(ScheduleCompletionActionKind.Sleep, 0));

        Assert.False(result.Succeeded);
        Assert.Contains("sleep unavailable", result.Message, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public async Task RunnerCapturesOutputWithoutUnboundedReadToEnd()
    {
        string shell = OperatingSystem.IsWindows() ? string.Empty : "/bin/sh";
        if (!File.Exists(shell))
        {
            return;
        }

        PlatformCommandRunner runner = new();
        PlatformCommandResult result = await runner.RunAsync(
            shell,
            ["-c", "python3 - <<'PY'\nprint('x' * 20000)\nPY"],
            TimeSpan.FromSeconds(10));

        Assert.Equal(0, result.ExitCode);
        Assert.True(result.StandardOutput.Length <= 4096 + Environment.NewLine.Length + "[output truncated]".Length);
        Assert.Contains("output truncated", result.StandardOutput, StringComparison.OrdinalIgnoreCase);
    }

    private sealed class RecordingRunner : IPlatformCommandRunner
    {
        public Task<PlatformCommandResult> RunAsync(
            string executablePath,
            IReadOnlyList<string> arguments,
            TimeSpan timeout,
            CancellationToken cancellationToken = default)
            => Task.FromResult(new PlatformCommandResult(0, string.Empty, string.Empty, false));
    }

    private sealed class RecordingLifetime : IApplicationLifetimeService
    {
        public Task RequestShutdownAsync(CancellationToken cancellationToken = default)
            => Task.CompletedTask;

        public Task ActivateMainWindowAsync(CancellationToken cancellationToken = default)
            => Task.CompletedTask;
    }
}
