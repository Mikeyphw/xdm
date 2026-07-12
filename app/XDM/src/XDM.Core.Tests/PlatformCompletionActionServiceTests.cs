using XDM.Core.Abstractions;
using XDM.Core.Scheduling;
using XDM.Platform;

namespace XDM.Core.Tests;

public sealed class PlatformCompletionActionServiceTests
{
    private static readonly string[] CommandArguments = ["literal argument", "$(not-a-shell)"];
    [Fact]
    public async Task RunsConfiguredExecutableWithoutShellExpansion()
    {
        string executable = Path.GetTempFileName();
        try
        {
            RecordingRunner runner = new();
            PlatformCompletionActionService service = new(
                runner,
                new RecordingLifetime(),
                new PlatformPowerCommandCatalog(
                    new Dictionary<ScheduleCompletionActionKind, PlatformPowerCommand>()));
            ScheduleCompletionAction action = new(
                ScheduleCompletionActionKind.RunCommand,
                0,
                executable,
                CommandArguments);

            CompletionActionResult result = await service.ExecuteAsync(action);

            Assert.True(result.Succeeded);
            Assert.Equal(executable, runner.ExecutablePath);
            Assert.Equal(CommandArguments, runner.Arguments);
        }
        finally
        {
            File.Delete(executable);
        }
    }

    [Fact]
    public async Task ExitActionUsesApplicationLifetime()
    {
        RecordingLifetime lifetime = new();
        PlatformCompletionActionService service = new(
            new RecordingRunner(),
            lifetime,
            new PlatformPowerCommandCatalog(
                new Dictionary<ScheduleCompletionActionKind, PlatformPowerCommand>()));

        CompletionActionResult result = await service.ExecuteAsync(
            new ScheduleCompletionAction(ScheduleCompletionActionKind.ExitApplication, 0));

        Assert.True(result.Succeeded);
        Assert.True(lifetime.ShutdownRequested);
    }

    private sealed class RecordingRunner : IPlatformCommandRunner
    {
        public string? ExecutablePath { get; private set; }

        public IReadOnlyList<string> Arguments { get; private set; } = [];

        public Task<PlatformCommandResult> RunAsync(
            string executablePath,
            IReadOnlyList<string> arguments,
            TimeSpan timeout,
            CancellationToken cancellationToken = default)
        {
            ExecutablePath = executablePath;
            Arguments = arguments.ToArray();
            return Task.FromResult(new PlatformCommandResult(0, string.Empty, string.Empty, false));
        }
    }

    private sealed class RecordingLifetime : IApplicationLifetimeService
    {
        public bool ShutdownRequested { get; private set; }

        public Task RequestShutdownAsync(CancellationToken cancellationToken = default)
        {
            ShutdownRequested = true;
            return Task.CompletedTask;
        }

        public Task ActivateMainWindowAsync(CancellationToken cancellationToken = default)
            => Task.CompletedTask;
    }
}
