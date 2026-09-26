using System.Collections.Concurrent;
using System.Diagnostics;
using System.Text.Json;

namespace XDM.Platform;

public sealed record DesktopMediaRequestHandoff(
    Uri ExactUri,
    IReadOnlyDictionary<string, string>? Headers = null,
    string? Cookie = null,
    string? Referer = null,
    string? UserAgent = null);

/// <summary>
/// Resolves app-local exact request material after Go chooses the capture/variant and typed operation.
/// Arbitrary argv/shell execution is intentionally impossible.
/// </summary>
public sealed class DesktopGoMediaBroker
{
    private readonly ConcurrentDictionary<string, DesktopMediaRequestHandoff> _captures = new(StringComparer.Ordinal);
    private readonly ConcurrentDictionary<string, DesktopMediaRequestHandoff> _variants = new(StringComparer.Ordinal);

    public void RegisterCapture(string captureId, DesktopMediaRequestHandoff handoff)
        => _captures[RequireIdentity(captureId)] = Validate(handoff);

    public void RegisterVariant(string variantId, DesktopMediaRequestHandoff handoff)
        => _variants[RequireIdentity(variantId)] = Validate(handoff);

    public async Task<JsonElement> ExecuteAsync(JsonElement payload, CancellationToken cancellationToken = default)
    {
        if (!payload.TryGetProperty("request_kind", out JsonElement requestKind)
            || requestKind.GetString() != "desktop_media_execute"
            || !payload.TryGetProperty("tool", out JsonElement tool)
            || tool.GetString() != "ffmpeg")
        {
            return Reject("unsupported_media_tool_request", "Only typed XGO desktop FFmpeg requests are supported.");
        }

        string captureId = payload.GetProperty("capture_id").GetString() ?? string.Empty;
        string? variantId = payload.TryGetProperty("variant_id", out JsonElement variant) ? variant.GetString() : null;
        DesktopMediaRequestHandoff? handoff = null;
        if (!string.IsNullOrWhiteSpace(variantId))
        {
            _variants.TryGetValue(variantId, out handoff);
        }
        if (handoff is null)
        {
            _captures.TryGetValue(captureId, out handoff);
        }
        if (handoff is null)
        {
            return Reject("request_handoff_missing", "Refresh this media capture before executing it.");
        }

        string operation = payload.GetProperty("operation").GetString() ?? string.Empty;
        if (operation is not ("finalize_adaptive" or "record_stream"))
        {
            return Reject("unsupported_ffmpeg_operation", "The requested FFmpeg operation is not allowlisted.");
        }
        string outputPath = payload.GetProperty("output_path").GetString() ?? string.Empty;
        if (string.IsNullOrWhiteSpace(outputPath))
        {
            return Reject("invalid_output_path", "A desktop media output path is required.");
        }
        string? parent = Path.GetDirectoryName(Path.GetFullPath(outputPath));
        if (!string.IsNullOrWhiteSpace(parent))
        {
            Directory.CreateDirectory(parent);
        }

        ProcessStartInfo start = new("ffmpeg")
        {
            UseShellExecute = false,
            RedirectStandardError = true,
            RedirectStandardOutput = true,
            CreateNoWindow = true
        };
        start.ArgumentList.Add("-hide_banner");
        start.ArgumentList.Add("-nostdin");
        start.ArgumentList.Add("-y");
        AddRequestOptions(start, handoff);
        start.ArgumentList.Add("-i");
        start.ArgumentList.Add(handoff.ExactUri.AbsoluteUri);
        if (operation == "record_stream"
            && payload.TryGetProperty("requested_duration_ms", out JsonElement requestedDuration)
            && requestedDuration.TryGetInt64(out long durationMs)
            && durationMs > 0)
        {
            start.ArgumentList.Add("-t");
            start.ArgumentList.Add(TimeSpan.FromMilliseconds(durationMs).TotalSeconds.ToString("0.###", System.Globalization.CultureInfo.InvariantCulture));
        }
        start.ArgumentList.Add("-c");
        start.ArgumentList.Add("copy");
        start.ArgumentList.Add(outputPath);

        try
        {
            using Process process = Process.Start(start) ?? throw new InvalidOperationException("FFmpeg could not be started.");
            Task<string> stderr = process.StandardError.ReadToEndAsync(cancellationToken);
            Task<string> stdout = process.StandardOutput.ReadToEndAsync(cancellationToken);
            await process.WaitForExitAsync(cancellationToken).ConfigureAwait(false);
            _ = await stdout.ConfigureAwait(false);
            _ = await stderr.ConfigureAwait(false);
            if (process.ExitCode != 0)
            {
                return JsonSerializer.SerializeToElement(new
                {
                    ok = false,
                    error_code = "ffmpeg_failed",
                    exit_code = process.ExitCode,
                    message = "FFmpeg failed; sensitive tool output was withheld."
                });
            }
            if (payload.TryGetProperty("maximum_bytes", out JsonElement maximum)
                && maximum.TryGetInt64(out long maxBytes)
                && maxBytes > 0
                && File.Exists(outputPath)
                && new FileInfo(outputPath).Length > maxBytes)
            {
                File.Delete(outputPath);
                return Reject("media_size_limit_exceeded", "The completed media exceeded the requested maximum size.");
            }
            return JsonSerializer.SerializeToElement(new { ok = true, output_path = outputPath, exit_code = process.ExitCode });
        }
        catch (System.ComponentModel.Win32Exception)
        {
            return Reject("ffmpeg_unavailable", "FFmpeg is not available on this desktop host.");
        }
    }

    private static void AddRequestOptions(ProcessStartInfo start, DesktopMediaRequestHandoff handoff)
    {
        if (!string.IsNullOrWhiteSpace(handoff.UserAgent))
        {
            start.ArgumentList.Add("-user_agent");
            start.ArgumentList.Add(handoff.UserAgent);
        }
        if (!string.IsNullOrWhiteSpace(handoff.Referer))
        {
            start.ArgumentList.Add("-referer");
            start.ArgumentList.Add(handoff.Referer);
        }
        List<string> headers = [];
        if (handoff.Headers is not null)
        {
            headers.AddRange(handoff.Headers.Select(static pair => $"{pair.Key}: {pair.Value}"));
        }
        if (!string.IsNullOrWhiteSpace(handoff.Cookie))
        {
            headers.Add("Cookie: " + handoff.Cookie);
        }
        if (headers.Count > 0)
        {
            start.ArgumentList.Add("-headers");
            start.ArgumentList.Add(string.Join("\r\n", headers) + "\r\n");
        }
    }

    private static DesktopMediaRequestHandoff Validate(DesktopMediaRequestHandoff handoff)
    {
        ArgumentNullException.ThrowIfNull(handoff);
        if (!handoff.ExactUri.IsAbsoluteUri || handoff.ExactUri.Scheme is not ("http" or "https"))
        {
            throw new InvalidDataException("Desktop media handoff requires an absolute HTTP(S) URI.");
        }
        return handoff;
    }

    private static string RequireIdentity(string value)
    {
        if (string.IsNullOrWhiteSpace(value) || value.Length > 160)
        {
            throw new ArgumentException("Desktop media handoff identity is invalid.", nameof(value));
        }
        return value;
    }

    private static JsonElement Reject(string code, string message)
        => JsonSerializer.SerializeToElement(new { ok = false, error_code = code, message });
}
