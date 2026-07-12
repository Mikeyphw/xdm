namespace XDM.Media;

public sealed record MediaCatalog(
    Uri Source,
    MediaKind Kind,
    string Title,
    bool IsLive,
    IReadOnlyList<MediaFormat> Formats,
    string Description,
    string Provider)
{
    public IReadOnlyList<MediaFormat> VideoFormats
        => Formats.Where(static format => format.StreamKind is MediaStreamKind.Video or MediaStreamKind.Muxed).ToArray();

    public IReadOnlyList<MediaFormat> AudioFormats
        => Formats.Where(static format => format.StreamKind == MediaStreamKind.Audio).ToArray();

    public IReadOnlyList<MediaFormat> SubtitleFormats
        => Formats.Where(static format => format.StreamKind == MediaStreamKind.Subtitle).ToArray();
}
