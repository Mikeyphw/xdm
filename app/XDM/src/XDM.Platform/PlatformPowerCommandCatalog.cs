using XDM.Core.Scheduling;

namespace XDM.Platform;

public sealed class PlatformPowerCommandCatalog
{
    private static readonly string[] WindowsShutdownArguments = ["/s", "/t", "0"];
    private static readonly string[] WindowsLogoutArguments = ["/l"];
    private static readonly string[] SystemctlCandidates = ["/usr/bin/systemctl", "/bin/systemctl"];
    private static readonly string[] LoginctlCandidates = ["/usr/bin/loginctl", "/bin/loginctl"];
    private static readonly string[] PoweroffArguments = ["poweroff"];
    private static readonly string[] SuspendArguments = ["suspend"];
    private static readonly string[] HibernateArguments = ["hibernate"];
    private static readonly string[] MacShutdownArguments = ["-h", "now"];
    private static readonly string[] MacSleepArguments = ["sleepnow"];
    private readonly IReadOnlyDictionary<ScheduleCompletionActionKind, PlatformPowerCommand> _commands;
    private readonly IReadOnlyDictionary<ScheduleCompletionActionKind, string> _unsupportedReasons;

    public PlatformPowerCommandCatalog(IReadOnlyDictionary<ScheduleCompletionActionKind, PlatformPowerCommand> commands)
        : this(commands, new Dictionary<ScheduleCompletionActionKind, string>())
    {
    }

    public PlatformPowerCommandCatalog(
        IReadOnlyDictionary<ScheduleCompletionActionKind, PlatformPowerCommand> commands,
        IReadOnlyDictionary<ScheduleCompletionActionKind, string> unsupportedReasons)
    {
        ArgumentNullException.ThrowIfNull(commands);
        ArgumentNullException.ThrowIfNull(unsupportedReasons);
        _commands = commands;
        _unsupportedReasons = unsupportedReasons;
    }

    public bool TryGet(ScheduleCompletionActionKind kind, out PlatformPowerCommand? command)
        => _commands.TryGetValue(kind, out command);

    public string GetUnsupportedReason(ScheduleCompletionActionKind kind)
        => _unsupportedReasons.TryGetValue(kind, out string? reason)
            ? reason
            : "No compatible, authorized system command was found.";

    public static PlatformPowerCommandCatalog Discover()
    {
        Dictionary<ScheduleCompletionActionKind, PlatformPowerCommand> commands = [];
        Dictionary<ScheduleCompletionActionKind, string> unsupported = [];
        if (OperatingSystem.IsWindows())
        {
            string systemDirectory = Environment.GetFolderPath(Environment.SpecialFolder.System);
            string shutdown = Path.Combine(systemDirectory, "shutdown.exe");
            AddIfPresent(commands, unsupported, ScheduleCompletionActionKind.Shutdown, shutdown, WindowsShutdownArguments);
            AddIfPresent(commands, unsupported, ScheduleCompletionActionKind.LogOut, shutdown, WindowsLogoutArguments);
            unsupported[ScheduleCompletionActionKind.Sleep] = "Windows sleep is not exposed as a supported shell command; XDM will not use an unsafe legacy DLL shortcut.";
            unsupported[ScheduleCompletionActionKind.Hibernate] = "Windows hibernate is not exposed as a supported shell command; XDM will not use an unsafe legacy DLL shortcut.";
        }
        else if (OperatingSystem.IsLinux())
        {
            if (IsSystemdAvailable())
            {
                string? systemctl = FindExecutable(SystemctlCandidates);
                if (systemctl is not null)
                {
                    commands[ScheduleCompletionActionKind.Shutdown] = new(systemctl, PoweroffArguments);
                    commands[ScheduleCompletionActionKind.Sleep] = new(systemctl, SuspendArguments);
                    commands[ScheduleCompletionActionKind.Hibernate] = new(systemctl, HibernateArguments);
                }
            }
            else
            {
                unsupported[ScheduleCompletionActionKind.Shutdown] = "systemd is not available in this session.";
                unsupported[ScheduleCompletionActionKind.Sleep] = "systemd suspend is not available in this session.";
                unsupported[ScheduleCompletionActionKind.Hibernate] = "systemd hibernate is not available in this session.";
            }

            string? loginctl = FindExecutable(LoginctlCandidates);
            string? sessionId = Environment.GetEnvironmentVariable("XDG_SESSION_ID");
            if (loginctl is not null && !string.IsNullOrWhiteSpace(sessionId))
            {
                commands[ScheduleCompletionActionKind.LogOut] = new(loginctl, ["terminate-session", sessionId]);
            }
            else
            {
                unsupported[ScheduleCompletionActionKind.LogOut] = "Current-session logout needs loginctl and XDG_SESSION_ID; XDM will not terminate every session owned by the user.";
            }
        }
        else if (OperatingSystem.IsMacOS())
        {
            AddIfPresent(commands, unsupported, ScheduleCompletionActionKind.Shutdown, "/sbin/shutdown", MacShutdownArguments);
            AddIfPresent(commands, unsupported, ScheduleCompletionActionKind.Sleep, "/usr/bin/pmset", MacSleepArguments);
            unsupported[ScheduleCompletionActionKind.Hibernate] = "Hibernate is not exposed as a supported macOS completion command.";
            unsupported[ScheduleCompletionActionKind.LogOut] = "Log out is not exposed as a supported macOS completion command.";
        }

        return new PlatformPowerCommandCatalog(commands, unsupported);
    }

    private static void AddIfPresent(
        Dictionary<ScheduleCompletionActionKind, PlatformPowerCommand> commands,
        Dictionary<ScheduleCompletionActionKind, string> unsupported,
        ScheduleCompletionActionKind kind,
        string path,
        IReadOnlyList<string> arguments)
    {
        if (File.Exists(path))
        {
            commands[kind] = new(path, arguments);
        }
        else
        {
            unsupported[kind] = $"Required executable was not found: {path}";
        }
    }

    private static bool IsSystemdAvailable()
        => Directory.Exists("/run/systemd/system") || Directory.Exists("/run/systemd/seats");

    private static string? FindExecutable(IEnumerable<string> candidates)
        => candidates.FirstOrDefault(File.Exists);
}

public sealed record PlatformPowerCommand(string ExecutablePath, IReadOnlyList<string> Arguments);
