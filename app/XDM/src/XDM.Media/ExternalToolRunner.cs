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
        Task<string> stdoutTask = ReadBoundedAsync(process.StandardOutput, maximumOutputBytes, linked.Token);
        Task<string> stderrTask = ReadBoundedAsync(process.StandardError, maximumOutputBytes, linked.Token);
        try
        {
            await process.WaitForExitAsync(linked.Token).ConfigureAwait(false);
            string stdout = await stdoutTask.ConfigureAwait(false);
            string stderr = await stderrTask.ConfigureAwait(false);
            return new ExternalToolResult(process.ExitCode, stdout, stderr);
        }
        catch (OperationCanceledException) when (timeoutSource.IsCancellationRequested && !cancellationToken.IsCancellationRequested)
        {
            TryKill(process);
            throw new TimeoutException($"{Path.GetFileName(executablePath)} exceeded the {timeout.TotalSeconds:0}-second timeout.");
        }
#pragma warning disable CA1031 // The process must be terminated for every non-fatal execution failure before preserving the original exception.
        catch
        {
            TryKill(process);
            throw;
        }
#pragma warning restore CA1031
    }

    private static async Task<string> ReadBoundedAsync(
        StreamReader reader,
        int maximumOutputBytes,
        CancellationToken cancellationToken)
    {
        char[] buffer = new char[4096];
        StringBuilder builder = new();
        int estimatedBytes = 0;
        while (true)
        {
            int count = await reader.ReadAsync(buffer.AsMemory(), cancellationToken).ConfigureAwait(false);
            if (count == 0)
            {
                return builder.ToString();
            }

            estimatedBytes += Encoding.UTF8.GetByteCount(buffer.AsSpan(0, count));
            if (estimatedBytes > maximumOutputBytes)
            {
                throw new InvalidDataException("External tool output exceeded the configured safety limit.");
            }

            builder.Append(buffer, 0, count);
        }
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
