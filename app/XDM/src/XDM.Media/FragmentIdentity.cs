using System.Security.Cryptography;
using System.Text;

namespace XDM.Media;

internal static class FragmentIdentity
{
    public static string Create(
        Uri uri,
        long? rangeOffset = null,
        long? rangeLength = null,
        string? contentKey = null)
        => ShortHash(
            uri.AbsoluteUri,
            rangeOffset?.ToString(System.Globalization.CultureInfo.InvariantCulture),
            rangeLength?.ToString(System.Globalization.CultureInfo.InvariantCulture),
            contentKey);

    public static string ShortHash(params string?[] parts)
    {
        using IncrementalHash hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        foreach (string? part in parts)
        {
            byte[] bytes = Encoding.UTF8.GetBytes(part ?? string.Empty);
            Span<byte> length = stackalloc byte[4];
            System.Buffers.Binary.BinaryPrimitives.WriteInt32BigEndian(length, bytes.Length);
            hash.AppendData(length);
            hash.AppendData(bytes);
        }

        return Convert.ToHexString(hash.GetHashAndReset());
    }

    public static string StableFileName(string id, string identity, string fallback)
    {
        string sanitized = SanitizeFileComponent(id, fallback);
        if (sanitized.Length > 72)
        {
            sanitized = sanitized[..72];
        }

        return $"{sanitized}-{identity[..16].ToLowerInvariant()}.part";
    }

    public static string FormatDirectory(string workspace, MediaFormat format)
    {
        string sanitized = SanitizeFileComponent(format.Id, "format");
        if (sanitized.Length > 80)
        {
            sanitized = sanitized[..80];
        }

        string key = ShortHash(format.Id, format.ManifestUri.AbsoluteUri, format.ProviderData)[..16].ToLowerInvariant();
        return Path.Combine(workspace, $"{sanitized}-{key}");
    }

    public static string SanitizeFileComponent(string value, string fallback)
    {
        HashSet<char> invalid = new(Path.GetInvalidFileNameChars());
        string sanitized = new string((value ?? string.Empty).Select(character =>
            invalid.Contains(character) || char.IsControl(character) ? '_' : character).ToArray()).Trim();
        sanitized = sanitized.Trim('.');
        return sanitized.Length == 0 ? fallback : sanitized;
    }
}
