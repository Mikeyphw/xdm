using System.Text.Json;
using System.Text.Json.Serialization;

namespace XDM.BrowserIntegration;

public sealed record BrowserClientInfo(
    string Name,
    string? Version = null,
    string? ExtensionVersion = null,
    string? Platform = null,
    string? ExtensionId = null,
    int ManifestVersion = 3,
    bool IncognitoAllowed = false,
    bool EnhancedAccessGranted = false,
    IReadOnlyList<string>? GrantedOrigins = null);

public sealed record BrowserNativeMessage(
    string ProtocolVersion,
    string RequestId,
    string Type,
    string? SessionId = null,
    BrowserClientInfo? Client = null,
    BrowserCaptureRequest? Capture = null,
    IReadOnlyList<BrowserCaptureRequest>? Captures = null,
    BrowserCaptureRules? Rules = null);

public sealed record BrowserNativeItemResult(
    string? RequestId,
    bool Accepted,
    string Reason,
    string? DownloadId = null);

public sealed record BrowserNativeResponse(
    string ProtocolVersion,
    string RequestId,
    string Type,
    bool Accepted,
    string? Reason = null,
    string? SessionId = null,
    string? HostVersion = null,
    IReadOnlyList<string>? Capabilities = null,
    int AcceptedCount = 0,
    int RejectedCount = 0,
    IReadOnlyList<BrowserNativeItemResult>? Items = null,
    string? MinimumExtensionVersion = null,
    string? Compatibility = null);

public static class BrowserNativeProtocol
{
    public const string ProtocolVersion = BrowserCaptureProtocol.ProtocolVersion;
    public const string MinimumExtensionVersion = "4.1.0";
    public const int MaximumMessageBytes = 256 * 1024;
    public const int MaximumBatchItems = 100;

    public static readonly IReadOnlyList<string> Capabilities =
    [
        "capture",
        "capture-batch",
        "acknowledged-takeover",
        "cookies-optional",
        "referer-optional",
        "user-agent",
        "request-body-optional",
        "capture-rules",
        "site-capture-modes",
        "incognito-policy",
        "permission-health",
        "health"
    ];

    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web)
    {
        UnmappedMemberHandling = JsonUnmappedMemberHandling.Disallow
    };

    public static BrowserNativeMessage Parse(ReadOnlySpan<byte> payload)
    {
        ValidateMessageSize(payload.Length);
        BrowserNativeMessage? message = JsonSerializer.Deserialize<BrowserNativeMessage>(payload, JsonOptions);
        if (message is null)
        {
            throw new InvalidDataException("Native message did not contain a request.");
        }

        message.Rules?.Validate();
        BrowserNativeMessage normalized = message with
        {
            ProtocolVersion = message.ProtocolVersion?.Trim() ?? string.Empty,
            RequestId = message.RequestId?.Trim() ?? string.Empty,
            Type = message.Type?.Trim().ToLowerInvariant() ?? string.Empty,
            SessionId = NormalizeOptional(message.SessionId),
            Client = NormalizeClient(message.Client),
            Capture = message.Capture?.Normalize(),
            Captures = message.Captures?.Select(NormalizeCapture).ToArray(),
            Rules = message.Rules?.Normalize()
        };

        Validate(normalized);
        return normalized;
    }

    public static byte[] Serialize(BrowserNativeResponse response)
    {
        ArgumentNullException.ThrowIfNull(response);
        byte[] payload = JsonSerializer.SerializeToUtf8Bytes(response, JsonOptions);
        ValidateMessageSize(payload.Length);
        return payload;
    }

    public static BrowserNativeResponse CreateHelloResponse(
        string requestId,
        string sessionId,
        string hostVersion,
        BrowserClientInfo client)
    {
        string compatibility = GetCompatibility(client.ExtensionVersion);
        bool accepted = string.Equals(compatibility, "compatible", StringComparison.Ordinal);
        return new BrowserNativeResponse(
            ProtocolVersion,
            requestId,
            "hello-ack",
            accepted,
            accepted ? "ready" : compatibility,
            accepted ? sessionId : null,
            hostVersion,
            Capabilities,
            MinimumExtensionVersion: MinimumExtensionVersion,
            Compatibility: compatibility);
    }

    public static BrowserNativeResponse CreateError(string requestId, string type, string reason)
        => new(
            ProtocolVersion,
            requestId,
            type,
            false,
            reason,
            MinimumExtensionVersion: MinimumExtensionVersion,
            Compatibility: reason == "protocol_mismatch" ? "protocol_mismatch" : null);

    public static string GetCompatibility(string? extensionVersion)
    {
        if (!Version.TryParse(extensionVersion, out Version? actual)
            || !Version.TryParse(MinimumExtensionVersion, out Version? minimum))
        {
            return "extension_outdated";
        }

        return actual >= minimum ? "compatible" : "extension_outdated";
    }

    private static BrowserClientInfo? NormalizeClient(BrowserClientInfo? client)
        => client is null
            ? null
            : client with
            {
                Name = client.Name?.Trim() ?? string.Empty,
                Version = NormalizeOptional(client.Version),
                ExtensionVersion = NormalizeOptional(client.ExtensionVersion),
                Platform = NormalizeOptional(client.Platform),
                ExtensionId = NormalizeOptional(client.ExtensionId),
                GrantedOrigins = client.GrantedOrigins?
                    .Where(static value => !string.IsNullOrWhiteSpace(value))
                    .Select(static value => value.Trim())
                    .Distinct(StringComparer.Ordinal)
                    .Take(64)
                    .ToArray()
            };

    private static void Validate(BrowserNativeMessage message)
    {
        if (!string.Equals(message.ProtocolVersion, ProtocolVersion, StringComparison.Ordinal))
        {
            throw new InvalidDataException($"Unsupported native messaging protocol '{message.ProtocolVersion}'. Expected '{ProtocolVersion}'.");
        }

        if (message.RequestId.Length is 0 or > 128
            || message.RequestId.Any(static character => !(char.IsAsciiLetterOrDigit(character) || character is '-' or '_' or '.')))
        {
            throw new InvalidDataException("Native message request ID is invalid.");
        }

        if (message.Type is not ("hello" or "health" or "capture" or "capture-batch"))
        {
            throw new InvalidDataException("Native message type is not supported.");
        }

        if (message.Type == "hello")
        {
            BrowserClientInfo? client = message.Client;
            if (client is null
                || string.IsNullOrWhiteSpace(client.Name)
                || client.Name.Length > 128
                || client.Version is { Length: > 512 }
                || client.ExtensionVersion is { Length: > 128 }
                || client.Platform is { Length: > 256 }
                || client.ExtensionId is { Length: > 256 }
                || client.ManifestVersion is < 2 or > 3
                || client.GrantedOrigins is { Count: > 64 }
                || client.GrantedOrigins?.Any(static origin => origin.Length > 512) == true)
            {
                throw new InvalidDataException("Native hello message requires valid bounded client information.");
            }

            if (message.SessionId is not null || message.Capture is not null || message.Captures is not null)
            {
                throw new InvalidDataException("Native hello message contains unexpected fields.");
            }

            return;
        }

        if (message.SessionId is null
            || message.SessionId.Length is < 32 or > 256
            || message.SessionId.Any(static character => !char.IsAsciiLetterOrDigit(character)))
        {
            throw new InvalidDataException("Native message session authentication is missing or invalid.");
        }

        if (message.Type == "health")
        {
            if (message.Capture is not null || message.Captures is not null)
            {
                throw new InvalidDataException("Native health message contains unexpected capture fields.");
            }

            return;
        }

        if (message.Type == "capture")
        {
            if (message.Capture is null || message.Captures is not null)
            {
                throw new InvalidDataException("Native capture message must contain exactly one capture.");
            }

            message.Capture.Validate();
            return;
        }

        if (message.Capture is not null
            || message.Captures is null
            || message.Captures.Count is 0 or > MaximumBatchItems)
        {
            throw new InvalidDataException($"Native capture batch must contain between 1 and {MaximumBatchItems} captures.");
        }

        foreach (BrowserCaptureRequest capture in message.Captures)
        {
            capture.Validate();
        }
    }

    private static void ValidateMessageSize(int length)
    {
        if (length is <= 0 or > MaximumMessageBytes)
        {
            throw new InvalidDataException("Native message is empty or exceeds 256 KiB.");
        }
    }

    private static BrowserCaptureRequest NormalizeCapture(BrowserCaptureRequest? capture)
        => capture?.Normalize()
            ?? throw new InvalidDataException("Native capture batch contains a null item.");

    private static string? NormalizeOptional(string? value)
        => string.IsNullOrWhiteSpace(value) ? null : value.Trim();
}
