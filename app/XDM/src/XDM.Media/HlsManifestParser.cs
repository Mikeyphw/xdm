using System.Globalization;

namespace XDM.Media;

internal static class HlsManifestParser
{
    private const int MaximumLines = 200_000;

    public static HlsManifest Parse(Uri manifestUri, string content)
    {
        ArgumentNullException.ThrowIfNull(manifestUri);
        ArgumentNullException.ThrowIfNull(content);
        string[] lines = content.Replace("\r\n", "\n", StringComparison.Ordinal).Split('\n');
        if (lines.Length > MaximumLines)
        {
            throw new InvalidDataException("HLS manifest exceeds the supported line limit.");
        }

        if (!lines.Any(static line => string.Equals(line.Trim(), "#EXTM3U", StringComparison.Ordinal)))
        {
            throw new InvalidDataException("The response is not a valid HLS playlist.");
        }

        List<HlsVariant> variants = [];
        List<HlsRendition> renditions = [];
        List<HlsSegment> segments = [];
        Dictionary<string, string>? pendingVariant = null;
        double pendingDuration = 0;
        long? pendingRangeLength = null;
        long? pendingRangeOffset = null;
        bool pendingDiscontinuity = false;
        HlsEncryptionKey? currentKey = null;
        HlsInitializationMap? currentMap = null;
        bool endList = false;
        int targetDuration = 6;
        long mediaSequence = 0;
        long nextSequence = 0;
        long? previousRangeEnd = null;

        foreach (string raw in lines)
        {
            string line = raw.Trim();
            if (line.Length == 0 || line == "#EXTM3U")
            {
                continue;
            }

            if (line.StartsWith("#EXT-X-STREAM-INF:", StringComparison.OrdinalIgnoreCase))
            {
                pendingVariant = ParseAttributes(line[(line.IndexOf(':') + 1)..]);
                continue;
            }

            if (line.StartsWith("#EXT-X-MEDIA:", StringComparison.OrdinalIgnoreCase))
            {
                Dictionary<string, string> attributes = ParseAttributes(line[(line.IndexOf(':') + 1)..]);
                string type = GetRequired(attributes, "TYPE");
                string groupId = GetRequired(attributes, "GROUP-ID");
                string name = GetRequired(attributes, "NAME");
                Uri? uri = attributes.TryGetValue("URI", out string? uriText)
                    ? Resolve(manifestUri, uriText)
                    : null;
                renditions.Add(new HlsRendition(
                    type,
                    groupId,
                    name,
                    attributes.GetValueOrDefault("LANGUAGE"),
                    uri,
                    ParseYesNo(attributes.GetValueOrDefault("DEFAULT")),
                    ParseYesNo(attributes.GetValueOrDefault("FORCED"))));
                continue;
            }

            if (line.StartsWith("#EXT-X-TARGETDURATION:", StringComparison.OrdinalIgnoreCase))
            {
                targetDuration = ParsePositiveInt(line[(line.IndexOf(':') + 1)..], "target duration");
                continue;
            }

            if (line.StartsWith("#EXT-X-MEDIA-SEQUENCE:", StringComparison.OrdinalIgnoreCase))
            {
                mediaSequence = ParseNonNegativeLong(line[(line.IndexOf(':') + 1)..], "media sequence");
                nextSequence = mediaSequence;
                continue;
            }

            if (line.StartsWith("#EXTINF:", StringComparison.OrdinalIgnoreCase))
            {
                string value = line[(line.IndexOf(':') + 1)..].Split(',', 2)[0];
                if (!double.TryParse(value, NumberStyles.Float, CultureInfo.InvariantCulture, out pendingDuration)
                    || pendingDuration < 0)
                {
                    throw new InvalidDataException("HLS segment duration is invalid.");
                }

                continue;
            }

            if (line.StartsWith("#EXT-X-BYTERANGE:", StringComparison.OrdinalIgnoreCase))
            {
                ParseByteRange(
                    line[(line.IndexOf(':') + 1)..],
                    previousRangeEnd,
                    out pendingRangeLength,
                    out pendingRangeOffset);
                continue;
            }

            if (line.StartsWith("#EXT-X-KEY:", StringComparison.OrdinalIgnoreCase))
            {
                currentKey = ParseKey(manifestUri, ParseAttributes(line[(line.IndexOf(':') + 1)..]));
                continue;
            }

            if (line.StartsWith("#EXT-X-MAP:", StringComparison.OrdinalIgnoreCase))
            {
                currentMap = ParseMap(manifestUri, ParseAttributes(line[(line.IndexOf(':') + 1)..]));
                continue;
            }

            if (line.Equals("#EXT-X-DISCONTINUITY", StringComparison.OrdinalIgnoreCase))
            {
                pendingDiscontinuity = true;
                continue;
            }

            if (line.Equals("#EXT-X-ENDLIST", StringComparison.OrdinalIgnoreCase))
            {
                endList = true;
                continue;
            }

            if (line.StartsWith('#'))
            {
                continue;
            }

            Uri resolved = Resolve(manifestUri, line);
            if (pendingVariant is not null)
            {
                ParseResolution(pendingVariant.GetValueOrDefault("RESOLUTION"), out int? width, out int? height);
                variants.Add(new HlsVariant(
                    resolved,
                    ParseNullableLong(pendingVariant.GetValueOrDefault("BANDWIDTH")),
                    width,
                    height,
                    ParseNullableDouble(pendingVariant.GetValueOrDefault("FRAME-RATE")),
                    pendingVariant.GetValueOrDefault("CODECS"),
                    pendingVariant.GetValueOrDefault("AUDIO"),
                    pendingVariant.GetValueOrDefault("SUBTITLES"),
                    pendingVariant.GetValueOrDefault("NAME")));
                pendingVariant = null;
                continue;
            }

            long sequence = nextSequence++;
            segments.Add(new HlsSegment(
                sequence,
                resolved,
                pendingDuration,
                pendingRangeLength,
                pendingRangeOffset,
                currentKey,
                currentMap,
                pendingDiscontinuity));
            if (pendingRangeLength is long length && pendingRangeOffset is long offset)
            {
                previousRangeEnd = checked(offset + length);
            }
            else
            {
                previousRangeEnd = null;
            }

            pendingDuration = 0;
            pendingRangeLength = null;
            pendingRangeOffset = null;
            pendingDiscontinuity = false;
        }

        return new HlsManifest(
            variants.Count > 0,
            endList,
            Math.Clamp(targetDuration, 1, 3600),
            mediaSequence,
            variants,
            renditions,
            segments);
    }

    internal static Dictionary<string, string> ParseAttributes(string value)
    {
        Dictionary<string, string> attributes = new(StringComparer.OrdinalIgnoreCase);
        int position = 0;
        while (position < value.Length)
        {
            int equals = value.IndexOf('=', position);
            if (equals < 0)
            {
                break;
            }

            string name = value[position..equals].Trim();
            position = equals + 1;
            string attributeValue;
            if (position < value.Length && value[position] == '"')
            {
                position++;
                int closing = position;
                while (closing < value.Length && value[closing] != '"')
                {
                    closing++;
                }

                if (closing >= value.Length)
                {
                    throw new InvalidDataException("HLS attribute contains an unterminated quoted value.");
                }

                attributeValue = value[position..closing];
                position = closing + 1;
            }
            else
            {
                int comma = value.IndexOf(',', position);
                int end = comma < 0 ? value.Length : comma;
                attributeValue = value[position..end].Trim();
                position = end;
            }

            if (name.Length > 0)
            {
                attributes[name] = attributeValue;
            }

            if (position < value.Length && value[position] == ',')
            {
                position++;
            }

            while (position < value.Length && char.IsWhiteSpace(value[position]))
            {
                position++;
            }
        }

        return attributes;
    }

    private static HlsEncryptionKey? ParseKey(Uri manifestUri, Dictionary<string, string> attributes)
    {
        string method = GetRequired(attributes, "METHOD");
        if (method.Equals("NONE", StringComparison.OrdinalIgnoreCase))
        {
            return null;
        }

        if (!method.Equals("AES-128", StringComparison.OrdinalIgnoreCase))
        {
            throw new NotSupportedException($"HLS encryption method '{method}' is not supported. Only AES-128 is supported.");
        }

        if (attributes.TryGetValue("KEYFORMAT", out string? keyFormat)
            && !keyFormat.Equals("identity", StringComparison.OrdinalIgnoreCase))
        {
            throw new NotSupportedException($"HLS key format '{keyFormat}' is not supported.");
        }

        Uri uri = Resolve(manifestUri, GetRequired(attributes, "URI"));
        byte[]? iv = attributes.TryGetValue("IV", out string? ivText) ? ParseInitializationVector(ivText) : null;
        return new HlsEncryptionKey(method.ToUpperInvariant(), uri, iv);
    }

    private static HlsInitializationMap ParseMap(Uri manifestUri, Dictionary<string, string> attributes)
    {
        Uri uri = Resolve(manifestUri, GetRequired(attributes, "URI"));
        long? length = null;
        long? offset = null;
        if (attributes.TryGetValue("BYTERANGE", out string? range))
        {
            ParseByteRange(range, null, out length, out offset);
        }

        return new HlsInitializationMap(uri, length, offset);
    }

    private static byte[] ParseInitializationVector(string value)
    {
        string hex = value.StartsWith("0x", StringComparison.OrdinalIgnoreCase) ? value[2..] : value;
        if (hex.Length is 0 or > 32 || hex.Any(static character => !Uri.IsHexDigit(character)))
        {
            throw new InvalidDataException("HLS AES-128 IV is invalid.");
        }

        hex = hex.PadLeft(32, '0');
        return Convert.FromHexString(hex);
    }

    private static void ParseByteRange(
        string value,
        long? implicitOffset,
        out long? length,
        out long? offset)
    {
        string[] parts = value.Split('@', 2, StringSplitOptions.TrimEntries);
        length = ParseNonNegativeLong(parts[0], "byte-range length");
        if (length <= 0)
        {
            throw new InvalidDataException("HLS byte-range length must be positive.");
        }

        offset = parts.Length == 2
            ? ParseNonNegativeLong(parts[1], "byte-range offset")
            : implicitOffset ?? 0;
    }

    private static void ParseResolution(string? value, out int? width, out int? height)
    {
        width = null;
        height = null;
        if (string.IsNullOrWhiteSpace(value))
        {
            return;
        }

        string[] parts = value.Split('x', 2, StringSplitOptions.TrimEntries);
        if (parts.Length == 2
            && int.TryParse(parts[0], NumberStyles.None, CultureInfo.InvariantCulture, out int parsedWidth)
            && int.TryParse(parts[1], NumberStyles.None, CultureInfo.InvariantCulture, out int parsedHeight)
            && parsedWidth > 0
            && parsedHeight > 0)
        {
            width = parsedWidth;
            height = parsedHeight;
        }
    }

    private static Uri Resolve(Uri baseUri, string value)
    {
        if (!Uri.TryCreate(baseUri, value, out Uri? resolved)
            || resolved.Scheme is not ("http" or "https"))
        {
            throw new InvalidDataException("HLS manifest contains an invalid or unsupported URI.");
        }

        return resolved;
    }

    private static string GetRequired(Dictionary<string, string> attributes, string name)
        => attributes.TryGetValue(name, out string? value) && !string.IsNullOrWhiteSpace(value)
            ? value
            : throw new InvalidDataException($"HLS attribute '{name}' is required.");

    private static bool ParseYesNo(string? value)
        => string.Equals(value, "YES", StringComparison.OrdinalIgnoreCase);

    private static int ParsePositiveInt(string value, string field)
    {
        if (!int.TryParse(value, NumberStyles.None, CultureInfo.InvariantCulture, out int parsed) || parsed <= 0)
        {
            throw new InvalidDataException($"HLS {field} is invalid.");
        }

        return parsed;
    }

    private static long ParseNonNegativeLong(string value, string field)
    {
        if (!long.TryParse(value, NumberStyles.None, CultureInfo.InvariantCulture, out long parsed) || parsed < 0)
        {
            throw new InvalidDataException($"HLS {field} is invalid.");
        }

        return parsed;
    }

    private static long? ParseNullableLong(string? value)
        => long.TryParse(value, NumberStyles.None, CultureInfo.InvariantCulture, out long parsed) && parsed >= 0
            ? parsed
            : null;

    private static double? ParseNullableDouble(string? value)
        => double.TryParse(value, NumberStyles.Float, CultureInfo.InvariantCulture, out double parsed) && parsed >= 0
            ? parsed
            : null;
}
