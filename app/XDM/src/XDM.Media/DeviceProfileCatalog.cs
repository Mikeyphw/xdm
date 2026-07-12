namespace XDM.Media;

internal static class DeviceProfileCatalog
{
    private static readonly DeviceFamily[] Families =
    [
        new("apple-iphone", "Apple iPhone", "aac"),
        new("apple-ipad", "Apple iPad", "aac"),
        new("android-phone", "Android phone", "aac"),
        new("android-tablet", "Android tablet", "aac"),
        new("samsung-galaxy", "Samsung Galaxy", "aac"),
        new("google-pixel", "Google Pixel", "aac"),
        new("amazon-fire", "Amazon Fire", "aac"),
        new("chromecast", "Google Chromecast", "aac"),
        new("roku", "Roku", "aac"),
        new("smart-tv", "Smart TV", "aac"),
        new("playstation", "PlayStation", "aac"),
        new("xbox", "Xbox", "aac")
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
        if (variant.Width == 0)
        {
            return new ConversionPresetDefinition(
                new ConversionPreset(
                    id,
                    name,
                    $"Extracts audio for {family.Name} using 192 kbps MP3.",
                    ConversionKind.AudioExtraction,
                    ".mp3"),
                "mp3",
                ["-vn", "-map", "0:a:0", "-map_metadata", "0", "-c:a", "libmp3lame", "-b:a", variant.AudioBitrate, "-id3v2_version", "3"],
                null,
                null);
        }

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
                $"Creates an MP4 tuned for {family.Name} at {variant.Name}.",
                ConversionKind.VideoTranscode,
                ".mp4"),
            "mp4",
            [
                "-map", "0:v:0", "-map", "0:a:0?", "-map_metadata", "0",
                "-vf", filter,
                "-r", variant.FrameRate.ToString(System.Globalization.CultureInfo.InvariantCulture),
                "-c:v", "libx264", "-preset", "medium", "-profile:v", "high", "-level", level,
                "-b:v", variant.VideoBitrate, "-maxrate", variant.VideoBitrate, "-bufsize", DoubleBitrate(variant.VideoBitrate),
                "-pix_fmt", "yuv420p",
                "-c:a", family.AudioCodec, "-b:a", variant.AudioBitrate,
                "-movflags", "+faststart"
            ],
            null,
            null);
    }

    private static string DoubleBitrate(string bitrate)
    {
        string numeric = bitrate.TrimEnd('k', 'K');
        return int.TryParse(numeric, System.Globalization.NumberStyles.None, System.Globalization.CultureInfo.InvariantCulture, out int value)
            ? $"{checked(value * 2)}k"
            : bitrate;
    }

    private sealed record DeviceFamily(string Id, string Name, string AudioCodec);

    private sealed record DeviceVariant(
        string Id,
        string Name,
        int Width,
        int Height,
        string VideoBitrate,
        string AudioBitrate,
        int FrameRate);
}
