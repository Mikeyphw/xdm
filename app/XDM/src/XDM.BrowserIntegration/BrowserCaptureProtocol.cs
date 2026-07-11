using System.Text.Json;

namespace XDM.BrowserIntegration;

public static class BrowserCaptureProtocol
{
    public const int MaximumPayloadBytes = 64 * 1024;
    public const string ProtocolVersion = "1";

    private static readonly JsonSerializerOptions SerializerOptions = new(JsonSerializerDefaults.Web);

    public static BrowserCaptureRequest Parse(ReadOnlySpan<byte> payload)
    {
        if (payload.Length == 0 || payload.Length > MaximumPayloadBytes)
        {
            throw new InvalidDataException("Browser capture payload is empty or exceeds 64 KiB.");
        }

        BrowserCaptureRequest? request = JsonSerializer.Deserialize<BrowserCaptureRequest>(payload, SerializerOptions);
        if (request is null)
        {
            throw new InvalidDataException("Browser capture payload did not contain a request.");
        }

        BrowserCaptureRequest normalized = request.Normalize();
        normalized.Validate();
        return normalized;
    }
}
