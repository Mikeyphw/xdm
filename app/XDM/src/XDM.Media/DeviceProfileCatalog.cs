using System.Globalization;

namespace XDM.Media;

internal static class DeviceProfileCatalog
{
    private static readonly DeviceFamily[] Families =
    [
        new("apple-iphone", "Apple iPhone", "aac", "high", "medium", 95, 100),
        new("apple-ipad", "Apple iPad", "aac", "high", "slow", 105, 110),
        new("android-phone", "Android phone", "aac", "main", "medium", 90, 95),
        new("android-tablet", "Android tablet", "aac", "high", "medium", 100, 105),
        new("samsung-galaxy", "Samsung Galaxy", "aac", "high", "slow", 110, 110),
        new("google-pixel", "Google Pixel", "aac", "high", "medium", 100, 100),
        new("amazon-fire", "Amazon Fire", "aac", "main", "medium", 85, 90),
        new("chromecast", "Google Chromecast", "aac", "high", "fast", 115, 110),
        new("roku", "Roku", "aac", "main", "fast", 90, 100),
        new("smart-tv", "Smart TV", "aac", "high", "slow", 125, 125),
        new("playstation", "PlayStation", "aac", "high", "slow", 130, 125),
        new("xbox", "Xbox", "aac", "high", "slow", 128, 125)
    ];

    private static readonly DeviceVariant[] Variants =
    [
        new("360p", "360p", 640, 360, "700k", "96k", 30),
        new("480p", "480p", 854, 480, "1200k", "128k", 30),
        new("540p", "540p", 960, 540, "1800k", "128k", 30),
        new("720p", "720p", 1280, 720, "2800k", "160k", 30),
        new("720p60", "720p 60 fps", 1280, 720, "4200k", "192k", 60),
        new("1080p", "1080p", 1920, 1080, "5000k", "192k", 30),
        new("1080p60", "1080p 60 fps", 1920, 1080, "8000k", "256k", 60),
        new("1440p", "1440p", 2560, 1440, "12000k", "256k", 30),
        new("2160p", "4K UHD", 3840, 2160, "24000k", "320k", 30),
        new("audio", "Audio only", 0, 0, "0", "192k", 0)
    ];

    public static ConversionPresetDefinition[] CreateDefinitions()
    {
        List<ConversionPresetDefinition> definitions = new(Families.Length * Variants.Length);
        foreach (DeviceFamily family in Families)
        {
            foreach (DeviceVariant variant in Variants)
            {
                definitions.Add(CreateDefinition(family, variant));
            }
        }

        return [.. definitions];
    }

    private static ConversionPresetDefinition CreateDefinition(
        DeviceFamily family,
        DeviceVariant variant)
    {
        string id = $"device-{family.Id}-{variant.Id}";
        string name = $"{family.Name} — {variant.Name}";
        string audioBitrate = ScaleBitrate(variant.AudioBitrate, family.AudioBitratePercent);
        if (variant.Width == 0)
        {
            return new ConversionPresetDefinition(
                new ConversionPreset(
                    id,
                    name,
                    $"Extracts audio for {family.Name} using {audioBitrate} MP3.",
                    ConversionKind.AudioExtraction,
                    ".mp3"),
                "mp3",
                ["-vn", "-map", "0:a:0", "-map_metadata", "0", "-metadata", $"xdm_device_family={family.Id}", "-c:a", "libmp3lame", "-b:a", audioBitrate, "-id3v2_version", "3"],
                null,
                null);
        }

        string videoBitrate = ScaleBitrate(variant.VideoBitrate, family.VideoBitratePercent);
        string maxRate = ScaleBitrate(variant.VideoBitrate, checked(family.VideoBitratePercent + 10));
        string filter = $"scale=w={variant.Width}:h={variant.Height}:force_original_aspect_ratio=decrease,pad={variant.Width}:{variant.Height}:(ow-iw)/2:(oh-ih)/2";
        string level = variant.Height switch
        {
            >= 2160 => "5.1",
            >= 1440 => "5.0",
            >= 1080 when variant.FrameRate >= 60 => "4.2",
            _ => "4.1"
        };
        return new ConversionPresetDefinition(
            new ConversionPreset(
                id,
                name,
                $"Creates an MP4 tuned for {family.Name} at {variant.Name} with family-specific bitrate and H.264 profile limits.",
                ConversionKind.VideoTranscode,
                ".mp4"),
            "mp4",
            [
                "-map", "0:v:0", "-map", "0:a:0?", "-map_metadata", "0", "-metadata", $"xdm_device_family={family.Id}",
                "-vf", filter,
                "-r", variant.FrameRate.ToString(CultureInfo.InvariantCulture),
                "-c:v", "libx264", "-preset", family.EncoderPreset, "-profile:v", family.VideoProfile, "-level", level,
                "-b:v", videoBitrate, "-maxrate", maxRate, "-bufsize", DoubleBitrate(maxRate),
                "-pix_fmt", "yuv420p",
                "-c:a", family.AudioCodec, "-b:a", audioBitrate,
                "-movflags", "+faststart"
            ],
            null,
            null);
    }

    private static string ScaleBitrate(
        string bitrate,
        int percent)
    {
        string normalized = bitrate.Trim();
        if (!normalized.EndsWith("k", StringComparison.OrdinalIgnoreCase))
        {
            return bitrate;
        }

        string numeric = normalized[..^1];
        return int.TryParse(numeric, NumberStyles.None, CultureInfo.InvariantCulture, out int value)
            ? $"{Math.Max(1, checked(value * percent / 100))}k"
            : bitrate;
    }

    private static string DoubleBitrate(string bitrate)
    {
        string numeric = bitrate.TrimEnd('k', 'K');
        return int.TryParse(numeric, NumberStyles.None, CultureInfo.InvariantCulture, out int value)
            ? $"{checked(value * 2)}k"
            : bitrate;
    }

    private sealed record DeviceFamily(
        string Id,
        string Name,
        string AudioCodec,
        string VideoProfile,
        string EncoderPreset,
        int VideoBitratePercent,
        int AudioBitratePercent);

    private sealed record DeviceVariant(
        string Id,
        string Name,
        int Width,
        int Height,
        string VideoBitrate,
        string AudioBitrate,
        int FrameRate);
}
