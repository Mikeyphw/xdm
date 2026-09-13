using System.Diagnostics;
using System.Text;

namespace XDM.Media;

public sealed class ExternalToolRunner : IExternalToolRunner
{
    public async Task<ExternalToolResult> RunAsync(
        string executablePath,
        IReadOnlyList<string> arguments,
        TimeSpan timeout,
        int maximumOutputBytes,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(executablePath);
        ArgumentNullException.ThrowIfNull(arguments);
        ArgumentOutOfRangeException.ThrowIfLessThanOrEqual(timeout, TimeSpan.Zero);
        ArgumentOutOfRangeException.ThrowIfLessThan(maximumOutputBytes, 1024);
        ArgumentOutOfRangeException.ThrowIfGreaterThan(maximumOutputBytes, 64 * 1024 * 1024);

        ProcessStartInfo startInfo = new()
        {
            FileName = executablePath,
            UseShellExecute = false,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            CreateNoWindow = true,
            StandardOutputEncoding = Encoding.UTF8,
            StandardErrorEncoding = Encoding.UTF8
        };
        foreach (string argument in arguments)
        {
            startInfo.ArgumentList.Add(argument);
        }

        using Process process = new() { StartInfo = startInfo };
        if (!process.Start())
        {
            throw new InvalidOperationException($"Unable to start {Path.GetFileName(executablePath)}.");
        }

        using CancellationTokenSource timeoutSource = new(timeout);
        using CancellationTokenSource linked = CancellationTokenSource.CreateLinkedTokenSource(
            cancellationToken,
            timeoutSource.Token);
        Task<string> stdoutTask = BoundedTextCapture.ReadRetainedAsync(
            process.StandardOutput,
            maximumOutputBytes);
        Task<string> stderrTask = BoundedTextCapture.ReadRetainedAsync(
            process.StandardError,
            maximumOutputBytes);
        try
        {
            await process.WaitForExitAsync(linked.Token).ConfigureAwait(false);
            await Task.WhenAll(stdoutTask, stderrTask).ConfigureAwait(false);
            string stdout = await stdoutTask.ConfigureAwait(false);
            string stderr = await stderrTask.ConfigureAwait(false);
            return new ExternalToolResult(process.ExitCode, stdout, stderr);
        }
        catch (OperationCanceledException) when (timeoutSource.IsCancellationRequested && !cancellationToken.IsCancellationRequested)
        {
            await TerminateAndDrainAsync(process, stdoutTask, stderrTask).ConfigureAwait(false);
            throw new TimeoutException($"{Path.GetFileName(executablePath)} exceeded the {timeout.TotalSeconds:0}-second timeout.");
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            await TerminateAndDrainAsync(process, stdoutTask, stderrTask).ConfigureAwait(false);
            throw;
        }
#pragma warning disable CA1031 // The process must be terminated for every non-fatal execution failure before preserving the original exception.
        catch
        {
            await TerminateAndDrainAsync(process, stdoutTask, stderrTask).ConfigureAwait(false);
            throw;
        }
#pragma warning restore CA1031
    }

    private static async Task TerminateAndDrainAsync(
        Process process,
        Task<string> stdoutTask,
        Task<string> stderrTask)
    {
        TryKill(process);
#pragma warning disable CA1031 // Best-effort cleanup must not hide the original timeout/cancellation/failure.
        try
        {
            await process.WaitForExitAsync(CancellationToken.None)
                .WaitAsync(TimeSpan.FromSeconds(15))
                .ConfigureAwait(false);
        }
        catch
        {
        }

        try
        {
            await Task.WhenAll(stdoutTask, stderrTask)
                .WaitAsync(TimeSpan.FromSeconds(15))
                .ConfigureAwait(false);
        }
        catch
        {
        }
#pragma warning restore CA1031
    }

    private static void TryKill(Process process)
    {
        try
        {
            if (!process.HasExited)
            {
                process.Kill(entireProcessTree: true);
            }
        }
        catch (InvalidOperationException)
        {
        }
        catch (System.ComponentModel.Win32Exception)
        {
        }
    }
}
