using System.Diagnostics;
using System.Globalization;
using System.Text;

namespace XDM.Media;

internal sealed class FfmpegConversionProcessRunner : IConversionProcessRunner
{
    private const int MaximumErrorBytes = 8 * 1024 * 1024;

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
        Task progressTask = ReadProgressAsync(process.StandardOutput, expectedDuration, progress, linked.Token);
        Task<string> errorTask = ReadBoundedErrorAsync(process.StandardError, linked.Token);
        try
        {
            await process.WaitForExitAsync(linked.Token).ConfigureAwait(false);
            await progressTask.ConfigureAwait(false);
            string standardError = await errorTask.ConfigureAwait(false);
            stopwatch.Stop();
            return new ConversionProcessResult(process.ExitCode, standardError, stopwatch.Elapsed);
        }
        catch (OperationCanceledException) when (timeoutSource.IsCancellationRequested && !cancellationToken.IsCancellationRequested)
        {
            TryKill(process);
            throw new TimeoutException($"FFmpeg exceeded the {timeout.TotalMinutes:0}-minute conversion timeout.");
        }
#pragma warning disable CA1031 // The process must be terminated for every non-fatal execution failure before preserving the original exception.
        catch
        {
            TryKill(process);
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
        IProgress<ConversionProgress>? progress,
        CancellationToken cancellationToken)
    {
        TimeSpan? processed = null;
        long? outputBytes = null;
        string? speed = null;
        while (true)
        {
            string? line = await reader.ReadLineAsync(cancellationToken).ConfigureAwait(false);
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
                progress?.Report(new ConversionProgress(
                    completed ? ConversionJobState.Finalizing : ConversionJobState.Converting,
                    completed ? "FFmpeg finished encoding; finalizing output." : "FFmpeg is converting the selected media.",
                    completed ? 1d : fraction,
                    processed,
                    outputBytes,
                    speed));
            }
        }
    }

    private static async Task<string> ReadBoundedErrorAsync(
        StreamReader reader,
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
            if (estimatedBytes > MaximumErrorBytes)
            {
                throw new InvalidDataException("FFmpeg diagnostic output exceeded the configured safety limit.");
            }

            builder.Append(buffer, 0, count);
        }
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
