namespace XDM.Media;

public sealed record MediaSelectionResult(
    MediaFormat? Video,
    MediaFormat? Audio,
    IReadOnlyList<MediaFormat> Subtitles);
