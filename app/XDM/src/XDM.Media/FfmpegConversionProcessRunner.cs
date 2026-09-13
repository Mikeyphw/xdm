using System.Diagnostics;
using System.Globalization;
using System.Text;

namespace XDM.Media;

internal sealed class FfmpegConversionProcessRunner : IConversionProcessRunner
{
    private const int MaximumRetainedDiagnosticBytes = 8 * 1024 * 1024;

    public async Task<ConversionProcessResult> RunAsync(
        string executablePath,
        IReadOnlyList<string> arguments,
        TimeSpan? expectedDuration,
        IProgress<ConversionProgress>? progress,
        CancellationToken cancellationToken)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(executablePath);
        ArgumentNullException.ThrowIfNull(arguments);

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
        Stopwatch stopwatch = Stopwatch.StartNew();
        if (!process.Start())
        {
            throw new InvalidOperationException("Unable to start FFmpeg.");
        }

        TimeSpan timeout = CalculateTimeout(expectedDuration);
        using CancellationTokenSource timeoutSource = new(timeout);
        using CancellationTokenSource linked = CancellationTokenSource.CreateLinkedTokenSource(
            cancellationToken,
            timeoutSource.Token);
        Task progressTask = ReadProgressAsync(process.StandardOutput, expectedDuration, progress);
        Task<string> errorTask = BoundedTextCapture.ReadRetainedAsync(
            process.StandardError,
            MaximumRetainedDiagnosticBytes);
        try
        {
            await process.WaitForExitAsync(linked.Token).ConfigureAwait(false);
            await AwaitPipesAsync(progressTask, errorTask).ConfigureAwait(false);
            string standardError = await errorTask.ConfigureAwait(false);
            stopwatch.Stop();
            return new ConversionProcessResult(process.ExitCode, standardError, stopwatch.Elapsed);
        }
        catch (OperationCanceledException) when (timeoutSource.IsCancellationRequested && !cancellationToken.IsCancellationRequested)
        {
            await TerminateAndDrainAsync(process, progressTask, errorTask).ConfigureAwait(false);
            throw new TimeoutException($"FFmpeg exceeded the {timeout.TotalMinutes:0}-minute conversion timeout.");
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            await TerminateAndDrainAsync(process, progressTask, errorTask).ConfigureAwait(false);
            throw;
        }
#pragma warning disable CA1031 // The process must be terminated for every non-fatal execution failure before preserving the original exception.
        catch
        {
            await TerminateAndDrainAsync(process, progressTask, errorTask).ConfigureAwait(false);
            throw;
        }
#pragma warning restore CA1031
    }

    internal static bool TryParseProgressTime(string key, string value, out TimeSpan processed)
    {
        processed = default;
        if (string.Equals(key, "out_time", StringComparison.Ordinal)
            && TimeSpan.TryParse(value, CultureInfo.InvariantCulture, out TimeSpan parsed))
        {
            processed = parsed;
            return true;
        }

        if ((string.Equals(key, "out_time_us", StringComparison.Ordinal)
                || string.Equals(key, "out_time_ms", StringComparison.Ordinal))
            && long.TryParse(value, NumberStyles.Integer, CultureInfo.InvariantCulture, out long microseconds)
            && microseconds >= 0
            && microseconds <= TimeSpan.MaxValue.Ticks / 10)
        {
            processed = TimeSpan.FromTicks(microseconds * 10);
            return true;
        }

        return false;
    }

    private static async Task ReadProgressAsync(
        StreamReader reader,
        TimeSpan? expectedDuration,
        IProgress<ConversionProgress>? progress)
    {
        TimeSpan? processed = null;
        long? outputBytes = null;
        string? speed = null;
        while (true)
        {
            string? line = await reader.ReadLineAsync().ConfigureAwait(false);
            if (line is null)
            {
                return;
            }

            int separator = line.IndexOf('=');
            if (separator <= 0)
            {
                continue;
            }

            string key = line[..separator];
            string value = line[(separator + 1)..];
            if (TryParseProgressTime(key, value, out TimeSpan parsed))
            {
                processed = parsed;
            }
            else if (string.Equals(key, "total_size", StringComparison.Ordinal)
                && long.TryParse(value, NumberStyles.Integer, CultureInfo.InvariantCulture, out long size)
                && size >= 0)
            {
                outputBytes = size;
            }
            else if (string.Equals(key, "speed", StringComparison.Ordinal))
            {
                speed = value;
            }
            else if (string.Equals(key, "progress", StringComparison.Ordinal))
            {
                double? fraction = expectedDuration is { Ticks: > 0 } duration && processed is not null
                    ? Math.Clamp(processed.Value.TotalSeconds / duration.TotalSeconds, 0, 1)
                    : null;
                bool completed = string.Equals(value, "end", StringComparison.Ordinal);
                ReportProgress(
                    progress,
                    new ConversionProgress(
                        completed ? ConversionJobState.Finalizing : ConversionJobState.Converting,
                        completed ? "FFmpeg finished encoding; finalizing output." : "FFmpeg is converting the selected media.",
                        completed ? 1d : fraction,
                        processed,
                        outputBytes,
                        speed));
            }
        }
    }

    private static async Task AwaitPipesAsync(
        Task progressTask,
        Task<string> errorTask)
        => await Task.WhenAll(progressTask, errorTask).ConfigureAwait(false);

    private static async Task TerminateAndDrainAsync(
        Process process,
        Task progressTask,
        Task<string> errorTask)
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
            await Task.WhenAll(progressTask, errorTask)
                .WaitAsync(TimeSpan.FromSeconds(15))
                .ConfigureAwait(false);
        }
        catch
        {
        }
#pragma warning restore CA1031
    }

    private static void ReportProgress(
        IProgress<ConversionProgress>? progress,
        ConversionProgress value)
    {
        if (progress is null)
        {
            return;
        }

#pragma warning disable CA1031 // A UI/subscriber progress exception must not stop FFmpeg pipe drainage.
        try
        {
            progress.Report(value);
        }
        catch
        {
        }
#pragma warning restore CA1031
    }

    private static TimeSpan CalculateTimeout(TimeSpan? expectedDuration)
    {
        if (expectedDuration is not { Ticks: > 0 } duration)
        {
            return TimeSpan.FromHours(24);
        }

        double minutes = Math.Clamp(duration.TotalMinutes * 8 + 30, 30, 24 * 60);
        return TimeSpan.FromMinutes(minutes);
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
