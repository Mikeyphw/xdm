namespace XDM.Media;

public static class ConversionPresetCatalog
{
    private static readonly ConversionPresetDefinition[] Definitions =
    [
        new(
            new ConversionPreset(
                "mp4-copy",
                "MP4 — remux without re-encoding",
                "Copies compatible video and audio streams into MP4. Fast and lossless.",
                ConversionKind.Remux,
                ".mp4"),
            "mp4",
            ["-map", "0:v:0?", "-map", "0:a:0?", "-map_metadata", "0", "-c", "copy", "-movflags", "+faststart"],
            new HashSet<string>(new[] { "h264", "hevc", "av1", "mpeg4" }, StringComparer.OrdinalIgnoreCase),
            new HashSet<string>(new[] { "aac", "mp3", "ac3", "eac3", "alac" }, StringComparer.OrdinalIgnoreCase)),
        new(
            new ConversionPreset(
                "mp4-h264-balanced",
                "MP4 — H.264/AAC balanced",
                "Transcodes video to broadly compatible H.264 and audio to AAC.",
                ConversionKind.VideoTranscode,
                ".mp4"),
            "mp4",
            ["-map", "0:v:0", "-map", "0:a:0?", "-map_metadata", "0", "-c:v", "libx264", "-preset", "medium", "-crf", "23", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart"],
            null,
            null),
        new(
            new ConversionPreset(
                "mp4-h264-compact",
                "MP4 — H.264/AAC compact",
                "Creates a smaller MP4 using H.264 CRF 28 and 128 kbps AAC audio.",
                ConversionKind.VideoTranscode,
                ".mp4"),
            "mp4",
            ["-map", "0:v:0", "-map", "0:a:0?", "-map_metadata", "0", "-c:v", "libx264", "-preset", "medium", "-crf", "28", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "128k", "-movflags", "+faststart"],
            null,
            null),
        new(
            new ConversionPreset(
                "mp3-192",
                "MP3 — 192 kbps",
                "Extracts the first audio stream as a constant 192 kbps MP3.",
                ConversionKind.AudioExtraction,
                ".mp3"),
            "mp3",
            ["-vn", "-map", "0:a:0", "-map_metadata", "0", "-c:a", "libmp3lame", "-b:a", "192k", "-id3v2_version", "3"],
            null,
            null),
        new(
            new ConversionPreset(
                "mp3-v0",
                "MP3 — V0 high quality",
                "Extracts the first audio stream as a high-quality variable-bitrate MP3.",
                ConversionKind.AudioExtraction,
                ".mp3"),
            "mp3",
            ["-vn", "-map", "0:a:0", "-map_metadata", "0", "-c:a", "libmp3lame", "-q:a", "0", "-id3v2_version", "3"],
            null,
            null)
    ];

    public static IReadOnlyList<ConversionPreset> Presets { get; } =
        Definitions.Select(static definition => definition.Preset).ToArray();

    internal static ConversionPresetDefinition GetDefinition(string presetId)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(presetId);
        ConversionPresetDefinition? definition = Definitions.FirstOrDefault(
            definition => string.Equals(definition.Preset.Id, presetId, StringComparison.Ordinal));
        return definition ?? throw new ArgumentException($"Unknown conversion preset '{presetId}'.", nameof(presetId));
    }
}

internal sealed record ConversionPresetDefinition(
    ConversionPreset Preset,
    string OutputFormat,
    IReadOnlyList<string> FfmpegArguments,
    HashSet<string>? CompatibleVideoCodecs,
    HashSet<string>? CompatibleAudioCodecs);
