using System.Buffers.Binary;
using System.Net.Http.Json;
using System.Text.Json;
using XDM.BrowserIntegration;

namespace XDM.NativeHost;

internal static class Program
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);

    public static async Task<int> Main()
    {
        try
        {
            byte[]? payload = await ReadMessageAsync(Console.OpenStandardInput()).ConfigureAwait(false);
            if (payload is null)
            {
                return 0;
            }

            _ = BrowserCaptureProtocol.Parse(payload);
            string token = await LoadTokenAsync().ConfigureAwait(false);
            using HttpClient client = new() { Timeout = TimeSpan.FromSeconds(15) };
            using HttpRequestMessage request = new(HttpMethod.Post, "http://127.0.0.1:9614/capture")
            {
                Content = new ByteArrayContent(payload)
            };
            request.Content.Headers.ContentType = new("application/json");
            request.Headers.Add("X-XDM-Token", token);
            using HttpResponseMessage response = await client.SendAsync(request).ConfigureAwait(false);
            await WriteMessageAsync(Console.OpenStandardOutput(), new
            {
                accepted = response.IsSuccessStatusCode,
                status = (int)response.StatusCode,
                protocolVersion = BrowserCaptureProtocol.ProtocolVersion
            }).ConfigureAwait(false);
            return response.IsSuccessStatusCode ? 0 : 2;
        }
        catch (Exception exception) when (exception is IOException or JsonException or HttpRequestException or InvalidDataException)
        {
            await WriteMessageAsync(Console.OpenStandardOutput(), new { accepted = false, error = exception.Message })
                .ConfigureAwait(false);
            return 1;
        }
    }

    private static async Task<byte[]?> ReadMessageAsync(Stream input)
    {
        byte[] lengthBytes = new byte[4];
        int read = await input.ReadAsync(lengthBytes).ConfigureAwait(false);
        if (read == 0)
        {
            return null;
        }

        if (read != 4)
        {
            throw new InvalidDataException("Native message length prefix is incomplete.");
        }

        int length = BinaryPrimitives.ReadInt32LittleEndian(lengthBytes);
        if (length is <= 0 or > BrowserCaptureProtocol.MaximumPayloadBytes)
        {
            throw new InvalidDataException("Native message size is invalid.");
        }

        byte[] payload = new byte[length];
        await input.ReadExactlyAsync(payload).ConfigureAwait(false);
        return payload;
    }

    private static async Task WriteMessageAsync(Stream output, object value)
    {
        byte[] payload = JsonSerializer.SerializeToUtf8Bytes(value, JsonOptions);
        byte[] length = new byte[4];
        BinaryPrimitives.WriteInt32LittleEndian(length, payload.Length);
        await output.WriteAsync(length).ConfigureAwait(false);
        await output.WriteAsync(payload).ConfigureAwait(false);
        await output.FlushAsync().ConfigureAwait(false);
    }

    private static async Task<string> LoadTokenAsync()
    {
        string path = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "xdm-modern",
            "browser-token.txt");
        string token = (await File.ReadAllTextAsync(path).ConfigureAwait(false)).Trim();
        if (token.Length < 32)
        {
            throw new InvalidDataException("XDM browser authentication token is unavailable.");
        }

        return token;
    }
}
