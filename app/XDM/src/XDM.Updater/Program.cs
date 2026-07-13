using System.ComponentModel;
using System.Diagnostics;
using System.Globalization;

namespace XDM.Updater;

internal static class Program
{
    public static async Task<int> Main(string[] args)
    {
        try
        {
            Arguments parsed = Arguments.Parse(args);
            if (parsed.Command == UpdateCommand.Apply && !parsed.ExternalRunner)
            {
                return LaunchExternalRunner(parsed, args);
            }

            UpdateTransactionExecutor executor = new();
            switch (parsed.Command)
            {
                case UpdateCommand.Apply:
                    await executor.ApplyAsync(parsed.TransactionPath, parsed.WaitProcessId);
                    break;
                case UpdateCommand.Rollback:
                    await executor.RollbackAsync(parsed.TransactionPath);
                    break;
                case UpdateCommand.MarkHealthy:
                    await UpdateTransactionExecutor.MarkHealthyAsync(parsed.TransactionPath);
                    break;
                default:
                    throw new InvalidOperationException("No updater command was selected.");
            }
            return 0;
        }
        catch (ArgumentException exception)
        {
            Console.Error.WriteLine(exception.Message);
            return 2;
        }
        catch (InvalidDataException exception)
        {
            Console.Error.WriteLine(exception.Message);
            return 3;
        }
        catch (IOException exception)
        {
            Console.Error.WriteLine(exception.Message);
            return 4;
        }
        catch (UnauthorizedAccessException exception)
        {
            Console.Error.WriteLine(exception.Message);
            return 5;
        }
        catch (Win32Exception exception)
        {
            Console.Error.WriteLine(exception.Message);
            return 6;
        }
        catch (InvalidOperationException exception)
        {
            Console.Error.WriteLine(exception.Message);
            return 7;
        }
    }

    private static int LaunchExternalRunner(Arguments parsed, string[] originalArguments)
    {
        string processPath = Environment.ProcessPath
            ?? throw new InvalidOperationException("The updater executable path is unavailable.");
        string runnerDirectory = Path.Combine(
            Path.GetDirectoryName(parsed.TransactionPath)!,
            "runner");
        Directory.CreateDirectory(runnerDirectory);
        string externalPath = Path.Combine(runnerDirectory, Path.GetFileName(processPath));
        File.Copy(processPath, externalPath, overwrite: true);
        if (!OperatingSystem.IsWindows())
        {
            File.SetUnixFileMode(
                externalPath,
                UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute
                | UnixFileMode.GroupRead | UnixFileMode.GroupExecute
                | UnixFileMode.OtherRead | UnixFileMode.OtherExecute);
        }

        ProcessStartInfo info = new()
        {
            FileName = externalPath,
            UseShellExecute = false,
            WorkingDirectory = runnerDirectory
        };
        foreach (string argument in originalArguments)
        {
            info.ArgumentList.Add(argument);
        }
        info.ArgumentList.Add("--external-runner");
        using Process process = Process.Start(info)
            ?? throw new InvalidOperationException("The external updater process could not be started.");
        return 0;
    }

    private enum UpdateCommand
    {
        Apply,
        Rollback,
        MarkHealthy
    }

    private sealed record Arguments(
        UpdateCommand Command,
        string TransactionPath,
        int? WaitProcessId,
        bool ExternalRunner)
    {
        public static Arguments Parse(string[] args)
        {
            ArgumentNullException.ThrowIfNull(args);
            UpdateCommand? command = null;
            string? transaction = null;
            int? waitPid = null;
            bool external = false;
            for (int index = 0; index < args.Length; index++)
            {
                switch (args[index])
                {
                    case "--apply":
                        command = UpdateCommand.Apply;
                        transaction = RequireValue(args, ref index, "--apply");
                        break;
                    case "--rollback":
                        command = UpdateCommand.Rollback;
                        transaction = RequireValue(args, ref index, "--rollback");
                        break;
                    case "--mark-healthy":
                        command = UpdateCommand.MarkHealthy;
                        transaction = RequireValue(args, ref index, "--mark-healthy");
                        break;
                    case "--wait-pid":
                        string value = RequireValue(args, ref index, "--wait-pid");
                        if (!int.TryParse(value, NumberStyles.None, CultureInfo.InvariantCulture, out int parsed)
                            || parsed <= 0)
                        {
                            throw new ArgumentException("--wait-pid requires a positive process ID.");
                        }
                        waitPid = parsed;
                        break;
                    case "--external-runner":
                        external = true;
                        break;
                    default:
                        throw new ArgumentException($"Unknown updater argument: {args[index]}");
                }
            }

            if (command is null || string.IsNullOrWhiteSpace(transaction))
            {
                throw new ArgumentException("Use --apply, --rollback, or --mark-healthy with a transaction path.");
            }
            return new Arguments(command.Value, Path.GetFullPath(transaction), waitPid, external);
        }

        private static string RequireValue(string[] args, ref int index, string option)
        {
            if (++index >= args.Length || string.IsNullOrWhiteSpace(args[index]))
            {
                throw new ArgumentException($"{option} requires a value.");
            }
            return args[index];
        }
    }
}
