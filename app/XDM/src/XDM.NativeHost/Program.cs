using System.Net;
using System.Net.Http.Json;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using XDM.BrowserIntegration;

namespace XDM.NativeHost;

internal static class Program
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web)
    {
        UnmappedMemberHandling = JsonUnmappedMemberHandling.Disallow
    };
    private static readonly TimeSpan LoopbackTimeout = TimeSpan.FromSeconds(20);

    public static async Task<int> Main()
    {
        Stream input = Console.OpenStandardInput();
        Stream output = Console.OpenStandardOutput();
        string sessionId = Convert.ToHexString(RandomNumberGenerator.GetBytes(32));
        string hostVersion = typeof(Program).Assembly.GetName().Version?.ToString() ?? "9.0.0";

        using HttpClient client = new() { Timeout = LoopbackTimeout };
        while (true)
        {
            byte[]? payload;
            try
            {
                payload = await NativeMessageFraming.ReadAsync(input).ConfigureAwait(false);
            }
            catch (Exception exception) when (exception is IOException or InvalidDataException)
            {
                await WriteResponseAsync(
                    output,
                    BrowserNativeProtocol.CreateError("unknown", "protocol-error", SanitizeError(exception.Message)))
                    .ConfigureAwait(false);
                return 1;
            }

            if (payload is null)
            {
                return 0;
            }

            BrowserNativeResponse response;
            try
            {
                BrowserNativeMessage message = BrowserNativeProtocol.Parse(payload);
                response = await HandleMessageAsync(message, sessionId, hostVersion, client).ConfigureAwait(false);
            }
            catch (Exception exception) when (exception is JsonException or InvalidDataException or HttpRequestException or IOException or OperationCanceledException)
            {
                response = BrowserNativeProtocol.CreateError(
                    TryReadRequestId(payload),
                    "protocol-error",
                    SanitizeError(exception.Message));
            }

            await WriteResponseAsync(output, response).ConfigureAwait(false);
        }
    }

    private static async Task<BrowserNativeResponse> HandleMessageAsync(
        BrowserNativeMessage message,
        string sessionId,
        string hostVersion,
        HttpClient client)
    {
        if (message.Type == "hello")
        {
            return BrowserNativeProtocol.CreateHelloResponse(message.RequestId, sessionId, hostVersion);
        }

        if (!FixedTimeEquals(message.SessionId, sessionId))
        {
            return BrowserNativeProtocol.CreateError(message.RequestId, $"{message.Type}-ack", "invalid_session");
        }

        if (message.Type == "health")
        {
            string? healthToken = await TryLoadTokenAsync().ConfigureAwait(false);
            bool ready = healthToken is not null && await ProbeHealthAsync(client, healthToken).ConfigureAwait(false);
            return new BrowserNativeResponse(
                BrowserNativeProtocol.ProtocolVersion,
                message.RequestId,
                "health-ack",
                ready,
                ready ? "ready" : "xdm_unavailable",
                sessionId,
                hostVersion,
                BrowserNativeProtocol.Capabilities);
        }

        IReadOnlyList<BrowserCaptureRequest> captures = message.Type == "capture"
            ? [message.Capture!]
            : message.Captures!;
        List<BrowserNativeItemResult> results = new(captures.Count);
        string? token = await TryLoadTokenAsync().ConfigureAwait(false);

        foreach (BrowserCaptureRequest captureValue in captures)
        {
            BrowserCaptureRequest capture = captureValue with
            {
                RequestId = captureValue.RequestId ?? message.RequestId
            };
            if (token is null)
            {
                results.Add(new BrowserNativeItemResult(capture.RequestId, false, "xdm_unavailable"));
                continue;
            }

            BrowserCaptureRuleDecision ruleDecision = BrowserCaptureRuleEvaluator.Evaluate(capture, message.Rules);
            if (!ruleDecision.Accepted)
            {
                results.Add(new BrowserNativeItemResult(capture.RequestId, false, ruleDecision.Reason));
                continue;
            }

            BrowserCaptureAcknowledgement acknowledgement = await SendCaptureAsync(client, token, capture)
                .ConfigureAwait(false);
            results.Add(new BrowserNativeItemResult(
                capture.RequestId,
                acknowledgement.Accepted,
                acknowledgement.Reason,
                acknowledgement.DownloadId));
        }

        int accepted = results.Count(static result => result.Accepted);
        int rejected = results.Count - accepted;
        bool allAccepted = accepted == results.Count;
        return new BrowserNativeResponse(
            BrowserNativeProtocol.ProtocolVersion,
            message.RequestId,
            message.Type == "capture" ? "capture-ack" : "capture-batch-ack",
            allAccepted,
            allAccepted ? "accepted" : accepted > 0 ? "partially_accepted" : results[0].Reason,
            sessionId,
            hostVersion,
            BrowserNativeProtocol.Capabilities,
            accepted,
            rejected,
            results);
    }

    private static async Task<BrowserCaptureAcknowledgement> SendCaptureAsync(
        HttpClient client,
        string token,
        BrowserCaptureRequest capture)
    {
        byte[] payload = BrowserCaptureProtocol.Serialize(capture);
        using HttpRequestMessage request = new(HttpMethod.Post, "http://127.0.0.1:9614/capture")
        {
            Content = new ByteArrayContent(payload)
        };
        request.Content.Headers.ContentType = new("application/json");
        request.Headers.Add("X-XDM-Token", token);

        using HttpResponseMessage response = await client.SendAsync(request).ConfigureAwait(false);
        BrowserCaptureAcknowledgement? acknowledgement = await response.Content
            .ReadFromJsonAsync<BrowserCaptureAcknowledgement>(JsonOptions)
            .ConfigureAwait(false);
        if (acknowledgement is null
            || string.IsNullOrWhiteSpace(acknowledgement.Reason)
            || !string.Equals(acknowledgement.RequestId, capture.RequestId, StringComparison.Ordinal))
        {
            return new BrowserCaptureAcknowledgement(capture.RequestId, false, "invalid_xdm_acknowledgement");
        }

        return acknowledgement.Accepted && response.IsSuccessStatusCode
            ? acknowledgement
            : acknowledgement with { Accepted = false };
    }

    private static async Task<bool> ProbeHealthAsync(HttpClient client, string token)
    {
        using HttpRequestMessage request = new(HttpMethod.Get, "http://127.0.0.1:9614/health");
        request.Headers.Add("X-XDM-Token", token);
        try
        {
            using HttpResponseMessage response = await client.SendAsync(request).ConfigureAwait(false);
            return response.StatusCode == HttpStatusCode.OK;
        }
        catch (Exception exception) when (exception is HttpRequestException or OperationCanceledException)
        {
            return false;
        }
    }

    private static async Task WriteResponseAsync(Stream output, BrowserNativeResponse response)
        => await NativeMessageFraming.WriteAsync(output, BrowserNativeProtocol.Serialize(response)).ConfigureAwait(false);

    private static bool FixedTimeEquals(string? supplied, string expected)
    {
        byte[] suppliedBytes = Encoding.UTF8.GetBytes(supplied ?? string.Empty);
        byte[] expectedBytes = Encoding.UTF8.GetBytes(expected);
        return suppliedBytes.Length == expectedBytes.Length
            && CryptographicOperations.FixedTimeEquals(suppliedBytes, expectedBytes);
    }

    private static string TryReadRequestId(byte[] payload)
    {
        try
        {
            using JsonDocument document = JsonDocument.Parse(payload);
            if (document.RootElement.TryGetProperty("requestId", out JsonElement requestId)
                && requestId.ValueKind == JsonValueKind.String)
            {
                string? value = requestId.GetString();
                if (!string.IsNullOrWhiteSpace(value)
                    && value.Length <= 128
                    && value.All(static character => char.IsAsciiLetterOrDigit(character) || character is '-' or '_' or '.'))
                {
                    return value;
                }
            }
        }
        catch (JsonException)
        {
        }

        return "unknown";
    }

    private static string SanitizeError(string error)
    {
        string sanitized = error
            .Replace('\r', ' ')
            .Replace('\n', ' ');
        return sanitized.Length <= 512 ? sanitized : sanitized[..512];
    }

    private static async Task<string?> TryLoadTokenAsync()
    {
        string path = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "xdm-modern",
            "browser-token.txt");
        try
        {
            string token = (await File.ReadAllTextAsync(path).ConfigureAwait(false)).Trim();
            return token.Length == 64
                && token.All(static character => char.IsAsciiHexDigit(character))
                    ? token
                    : null;
        }
        catch (IOException)
        {
            return null;
        }
        catch (UnauthorizedAccessException)
        {
            return null;
        }
    }
}
