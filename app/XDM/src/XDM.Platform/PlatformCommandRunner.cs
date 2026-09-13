using System.ComponentModel;
using System.Diagnostics;
using System.Text;

namespace XDM.Platform;

public sealed class PlatformCommandRunner : IPlatformCommandRunner
{
    private const int MaximumCapturedCharacters = 4096;
    private static readonly TimeSpan KillWaitTimeout = TimeSpan.FromSeconds(5);

    public async Task<PlatformCommandResult> RunAsync(
        string executablePath,
        IReadOnlyList<string> arguments,
        TimeSpan timeout,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(executablePath);
        ArgumentNullException.ThrowIfNull(arguments);
        ArgumentOutOfRangeException.ThrowIfLessThanOrEqual(timeout, TimeSpan.Zero);
        if (!Path.IsPathFullyQualified(executablePath) || !File.Exists(executablePath))
        {
            throw new FileNotFoundException("The configured executable was not found.", executablePath);
        }

        ProcessStartInfo startInfo = new()
        {
            FileName = executablePath,
            UseShellExecute = false,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            CreateNoWindow = true,
            WorkingDirectory = Path.GetDirectoryName(executablePath) ?? Environment.CurrentDirectory
        };
        foreach (string argument in arguments)
        {
            startInfo.ArgumentList.Add(argument);
        }

        BoundedCapture output = new(MaximumCapturedCharacters);
        BoundedCapture error = new(MaximumCapturedCharacters);
        using Process process = new() { StartInfo = startInfo, EnableRaisingEvents = true };
        process.OutputDataReceived += (_, eventArgs) => output.AppendLine(eventArgs.Data);
        process.ErrorDataReceived += (_, eventArgs) => error.AppendLine(eventArgs.Data);
        try
        {
            if (!process.Start())
            {
                return new PlatformCommandResult(-1, string.Empty, "The configured process could not be started.", false);
            }
        }
        catch (Exception exception) when (exception is Win32Exception or InvalidOperationException or UnauthorizedAccessException)
        {
            return new PlatformCommandResult(-1, string.Empty, exception.Message, false);
        }

        process.BeginOutputReadLine();
        process.BeginErrorReadLine();
        using CancellationTokenSource timeoutCancellation = new(timeout);
        using CancellationTokenSource linkedCancellation = CancellationTokenSource.CreateLinkedTokenSource(
            cancellationToken,
            timeoutCancellation.Token);
        bool timedOut = false;
        bool killFailed = false;
        try
        {
            await process.WaitForExitAsync(linkedCancellation.Token).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (timeoutCancellation.IsCancellationRequested && !cancellationToken.IsCancellationRequested)
        {
            timedOut = true;
            killFailed = !TryKill(process);
            if (!killFailed)
            {
                using CancellationTokenSource killWait = new(KillWaitTimeout);
                try
                {
                    await process.WaitForExitAsync(killWait.Token).ConfigureAwait(false);
                }
                catch (OperationCanceledException)
                {
                    killFailed = true;
                }
            }
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            _ = TryKill(process);
            throw;
        }

        if (process.HasExited)
        {
            process.WaitForExit();
        }

        string stderr = error.ToString();
        if (timedOut && killFailed)
        {
            stderr = string.IsNullOrWhiteSpace(stderr)
                ? "The command timed out and XDM could not confirm process-tree termination."
                : stderr + Environment.NewLine + "The command timed out and XDM could not confirm process-tree termination.";
        }

        int exitCode = process.HasExited ? process.ExitCode : -1;
        return new PlatformCommandResult(exitCode, output.ToString(), stderr, timedOut, killFailed);
    }

    private static bool TryKill(Process process)
    {
        try
        {
            if (!process.HasExited)
            {
                process.Kill(entireProcessTree: true);
            }

            return true;
        }
        catch (InvalidOperationException)
        {
            return process.HasExited;
        }
        catch (Win32Exception)
        {
            return false;
        }
        catch (NotSupportedException)
        {
            return false;
        }
    }

    private sealed class BoundedCapture(int maximumCharacters)
    {
        private readonly object _sync = new();
        private readonly StringBuilder _builder = new(Math.Min(maximumCharacters, 1024));
        private bool _truncated;

        public void AppendLine(string? value)
        {
            if (value is null)
            {
                return;
            }

            lock (_sync)
            {
                if (_builder.Length >= maximumCharacters)
                {
                    _truncated = true;
                    return;
                }

                int remaining = maximumCharacters - _builder.Length;
                string line = value.Length + Environment.NewLine.Length <= remaining
                    ? value + Environment.NewLine
                    : value[..Math.Max(0, remaining)];
                _builder.Append(line);
                if (line.Length < value.Length + Environment.NewLine.Length)
                {
                    _truncated = true;
                }
            }
        }

        public override string ToString()
        {
            lock (_sync)
            {
                return _truncated
                    ? _builder.ToString() + Environment.NewLine + "[output truncated]"
                    : _builder.ToString();
            }
        }
    }
}
