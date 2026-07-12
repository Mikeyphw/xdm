using XDM.Core.Abstractions;
using XDM.Core.Scheduling;

namespace XDM.Platform;

public sealed class PlatformCompletionActionService : ICompletionActionService
{
    private static readonly ScheduleCompletionActionKind[] PowerActionKinds =
    [
        ScheduleCompletionActionKind.Shutdown,
        ScheduleCompletionActionKind.Sleep,
        ScheduleCompletionActionKind.Hibernate,
        ScheduleCompletionActionKind.LogOut
    ];
    private static readonly TimeSpan CommandTimeout = TimeSpan.FromMinutes(5);
    private readonly IPlatformCommandRunner _runner;
    private readonly IApplicationLifetimeService _applicationLifetime;
    private readonly PlatformPowerCommandCatalog _catalog;

    public PlatformCompletionActionService(
        IPlatformCommandRunner runner,
        IApplicationLifetimeService applicationLifetime)
        : this(runner, applicationLifetime, PlatformPowerCommandCatalog.Discover())
    {
    }

    public PlatformCompletionActionService(
        IPlatformCommandRunner runner,
        IApplicationLifetimeService applicationLifetime,
        PlatformPowerCommandCatalog catalog)
    {
        _runner = runner;
        _applicationLifetime = applicationLifetime;
        _catalog = catalog;
    }

    public IReadOnlyList<CompletionActionCapability> GetCapabilities()
    {
        List<CompletionActionCapability> capabilities =
        [
            new(ScheduleCompletionActionKind.None, true, "No completion action."),
            new(ScheduleCompletionActionKind.ExitApplication, true, "Exit XDM after the countdown."),
            new(ScheduleCompletionActionKind.RunCommand, true, "Run a configured absolute executable without a shell.")
        ];
        foreach (ScheduleCompletionActionKind kind in PowerActionKinds)
        {
            bool supported = _catalog.TryGet(kind, out _);
            capabilities.Add(new CompletionActionCapability(
                kind,
                supported,
                supported ? "Supported by this platform." : "No compatible system command was found."));
        }

        return capabilities;
    }

    public async Task<CompletionActionResult> ExecuteAsync(
        ScheduleCompletionAction action,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(action);
        ScheduleCompletionAction normalized = action.Normalize();
        if (normalized.Kind == ScheduleCompletionActionKind.None)
        {
            return new CompletionActionResult(normalized.Kind, true, "No completion action was requested.");
        }

        if (normalized.Kind == ScheduleCompletionActionKind.ExitApplication)
        {
            await _applicationLifetime.RequestShutdownAsync(cancellationToken).ConfigureAwait(false);
            return new CompletionActionResult(normalized.Kind, true, "Application exit requested.");
        }

        string executable;
        IReadOnlyList<string> arguments;
        if (normalized.Kind == ScheduleCompletionActionKind.RunCommand)
        {
            executable = normalized.ExecutablePath
                ?? throw new InvalidOperationException("A completion command executable is required.");
            if (!Path.IsPathFullyQualified(executable) || !File.Exists(executable))
            {
                return new CompletionActionResult(normalized.Kind, false, "The configured completion executable was not found.");
            }

            arguments = normalized.Arguments ?? [];
        }
        else if (_catalog.TryGet(normalized.Kind, out PlatformPowerCommand? command) && command is not null)
        {
            executable = command.ExecutablePath;
            arguments = command.Arguments;
        }
        else
        {
            return new CompletionActionResult(normalized.Kind, false, "This completion action is not supported on the current platform.");
        }

        PlatformCommandResult result = await _runner
            .RunAsync(executable, arguments, CommandTimeout, cancellationToken)
            .ConfigureAwait(false);
        bool succeeded = !result.TimedOut && result.ExitCode == 0;
        string message = result.TimedOut
            ? "The completion command timed out."
            : succeeded
                ? "The completion command finished successfully."
                : $"The completion command exited with code {result.ExitCode}.";
        return new CompletionActionResult(normalized.Kind, succeeded, message);
    }
}
