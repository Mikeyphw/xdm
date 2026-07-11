using System.Text.Json;
using System.Text.Json.Serialization;

namespace XDM.Parity;

public static class ParityManifestLoader
{
    private static readonly JsonSerializerOptions SerializerOptions = CreateOptions();

    public static async Task<ParityManifest> LoadAsync(
        Stream stream,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(stream);
        ParityManifest? manifest = await JsonSerializer.DeserializeAsync<ParityManifest>(
            stream,
            SerializerOptions,
            cancellationToken).ConfigureAwait(false);
        return manifest ?? throw new InvalidDataException("The parity manifest is empty.");
    }

    public static async Task<ParityManifest> LoadAsync(
        string path,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(path);
        await using FileStream stream = File.OpenRead(path);
        return await LoadAsync(stream, cancellationToken).ConfigureAwait(false);
    }

    private static JsonSerializerOptions CreateOptions()
    {
        JsonSerializerOptions options = new(JsonSerializerDefaults.Web)
        {
            PropertyNameCaseInsensitive = true,
            ReadCommentHandling = JsonCommentHandling.Skip
        };
        options.Converters.Add(new JsonStringEnumConverter(JsonNamingPolicy.CamelCase));
        return options;
    }
}
