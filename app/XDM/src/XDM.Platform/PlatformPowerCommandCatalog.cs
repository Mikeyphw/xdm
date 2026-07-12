using XDM.Core.Scheduling;

namespace XDM.Platform;

public sealed class PlatformPowerCommandCatalog
{
    private static readonly string[] WindowsShutdownArguments = ["/s", "/t", "0"];
    private static readonly string[] WindowsLogoutArguments = ["/l"];
    private static readonly string[] WindowsSleepArguments = ["powrprof.dll,SetSuspendState", "0,1,0"];
    private static readonly string[] WindowsHibernateArguments = ["powrprof.dll,SetSuspendState", "Hibernate"];
    private static readonly string[] SystemctlCandidates = ["/usr/bin/systemctl", "/bin/systemctl"];
    private static readonly string[] LoginctlCandidates = ["/usr/bin/loginctl", "/bin/loginctl"];
    private static readonly string[] PoweroffArguments = ["poweroff"];
    private static readonly string[] SuspendArguments = ["suspend"];
    private static readonly string[] HibernateArguments = ["hibernate"];
    private static readonly string[] MacShutdownArguments = ["-h", "now"];
    private static readonly string[] MacSleepArguments = ["sleepnow"];
    private readonly IReadOnlyDictionary<ScheduleCompletionActionKind, PlatformPowerCommand> _commands;

    public PlatformPowerCommandCatalog(IReadOnlyDictionary<ScheduleCompletionActionKind, PlatformPowerCommand> commands)
    {
        ArgumentNullException.ThrowIfNull(commands);
        _commands = commands;
    }

    public bool TryGet(ScheduleCompletionActionKind kind, out PlatformPowerCommand? command)
        => _commands.TryGetValue(kind, out command);

    public static PlatformPowerCommandCatalog Discover()
    {
        Dictionary<ScheduleCompletionActionKind, PlatformPowerCommand> commands = [];
        if (OperatingSystem.IsWindows())
        {
            string systemDirectory = Environment.GetFolderPath(Environment.SpecialFolder.System);
            string shutdown = Path.Combine(systemDirectory, "shutdown.exe");
            string rundll32 = Path.Combine(systemDirectory, "rundll32.exe");
            AddIfPresent(commands, ScheduleCompletionActionKind.Shutdown, shutdown, WindowsShutdownArguments);
            AddIfPresent(commands, ScheduleCompletionActionKind.LogOut, shutdown, WindowsLogoutArguments);
            AddIfPresent(commands, ScheduleCompletionActionKind.Sleep, rundll32, WindowsSleepArguments);
            AddIfPresent(commands, ScheduleCompletionActionKind.Hibernate, rundll32, WindowsHibernateArguments);
        }
        else if (OperatingSystem.IsLinux())
        {
            string? systemctl = FindExecutable(SystemctlCandidates);
            if (systemctl is not null)
            {
                commands[ScheduleCompletionActionKind.Shutdown] = new(systemctl, PoweroffArguments);
                commands[ScheduleCompletionActionKind.Sleep] = new(systemctl, SuspendArguments);
                commands[ScheduleCompletionActionKind.Hibernate] = new(systemctl, HibernateArguments);
            }

            string? loginctl = FindExecutable(LoginctlCandidates);
            string? user = Environment.UserName;
            if (loginctl is not null && !string.IsNullOrWhiteSpace(user))
            {
                commands[ScheduleCompletionActionKind.LogOut] = new(loginctl, ["terminate-user", user]);
            }
        }
        else if (OperatingSystem.IsMacOS())
        {
            AddIfPresent(commands, ScheduleCompletionActionKind.Shutdown, "/sbin/shutdown", MacShutdownArguments);
            AddIfPresent(commands, ScheduleCompletionActionKind.Sleep, "/usr/bin/pmset", MacSleepArguments);
        }

        return new PlatformPowerCommandCatalog(commands);
    }

    private static void AddIfPresent(
        Dictionary<ScheduleCompletionActionKind, PlatformPowerCommand> commands,
        ScheduleCompletionActionKind kind,
        string path,
        IReadOnlyList<string> arguments)
    {
        if (File.Exists(path))
        {
            commands[kind] = new PlatformPowerCommand(path, arguments);
        }
    }

    private static string? FindExecutable(IEnumerable<string> candidates)
        => candidates.FirstOrDefault(File.Exists);
}

public sealed record PlatformPowerCommand(string ExecutablePath, IReadOnlyList<string> Arguments);
