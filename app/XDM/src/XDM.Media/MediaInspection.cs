namespace XDM.Media;

public sealed record MediaInspection(
    TimeSpan? Duration,
    string? FormatName,
    string? VideoCodec,
    string? AudioCodec,
    bool HasVideo,
    bool HasAudio);
