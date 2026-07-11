using System.Text.Json;
using System.Text.Json.Serialization;

namespace XDM.BrowserIntegration;

public static class BrowserCaptureProtocol
{
    public const int MaximumPayloadBytes = 128 * 1024;
    public const string ProtocolVersion = "2.0";

    private static readonly JsonSerializerOptions SerializerOptions = new(JsonSerializerDefaults.Web)
    {
        UnmappedMemberHandling = JsonUnmappedMemberHandling.Disallow
    };

    public static BrowserCaptureRequest Parse(ReadOnlySpan<byte> payload)
    {
        ValidatePayloadSize(payload.Length);
        BrowserCaptureRequest? request = JsonSerializer.Deserialize<BrowserCaptureRequest>(payload, SerializerOptions);
        if (request is null)
        {
            throw new InvalidDataException("Browser capture payload did not contain a request.");
        }

        BrowserCaptureRequest normalized = request.Normalize();
        normalized.Validate();
        return normalized;
    }

    public static byte[] Serialize(BrowserCaptureRequest request)
    {
        ArgumentNullException.ThrowIfNull(request);
        BrowserCaptureRequest normalized = request.Normalize();
        normalized.Validate();
        byte[] payload = JsonSerializer.SerializeToUtf8Bytes(normalized, SerializerOptions);
        ValidatePayloadSize(payload.Length);
        return payload;
    }

    private static void ValidatePayloadSize(int payloadLength)
    {
        if (payloadLength is <= 0 or > MaximumPayloadBytes)
        {
            throw new InvalidDataException("Browser capture payload is empty or exceeds 128 KiB.");
        }
    }
}
