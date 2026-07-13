namespace XDM.Media;

public static class MediaSelectionPolicy
{
    public static MediaSelectionResult Select(MediaCatalog catalog, MediaSelectionRequest request)
    {
        ArgumentNullException.ThrowIfNull(catalog);
        ArgumentNullException.ThrowIfNull(request);

        MediaFormat? video = SelectVideo(catalog.VideoFormats, request);
        MediaFormat? audio = SelectAudio(catalog.AudioFormats, video, request.AudioLanguage);
        MediaFormat[] subtitles = SelectSubtitles(catalog.SubtitleFormats, request.SubtitleLanguage);
        return new MediaSelectionResult(video, audio, subtitles);
    }

    private static MediaFormat? SelectVideo(
        IReadOnlyList<MediaFormat> formats,
        MediaSelectionRequest request)
    {
        if (request.AudioOnly)
        {
            return null;
        }

        if (request.PreferSmallest)
        {
            return formats
                .OrderBy(static format => format.Height ?? int.MaxValue)
                .ThenBy(static format => format.Bandwidth ?? long.MaxValue)
                .FirstOrDefault();
        }

        IEnumerable<MediaFormat> eligible = request.MaximumHeight is int height
            ? formats.Where(format => (format.Height ?? 0) <= height)
            : formats;
        return eligible
            .OrderByDescending(static format => format.IsDefault)
            .ThenByDescending(static format => format.Height ?? 0)
            .ThenByDescending(static format => format.Bandwidth ?? 0)
            .FirstOrDefault()
            ?? formats.OrderBy(static format => format.Height ?? int.MaxValue).FirstOrDefault();
    }

    private static MediaFormat? SelectAudio(
        IReadOnlyList<MediaFormat> formats,
        MediaFormat? video,
        string? language)
    {
        bool anyLanguage = string.IsNullOrWhiteSpace(language)
            || string.Equals(language, "Any", StringComparison.OrdinalIgnoreCase);
        if (video?.StreamKind == MediaStreamKind.Muxed && anyLanguage)
        {
            return null;
        }

        IEnumerable<MediaFormat> candidates = formats;
        if (!anyLanguage)
        {
            MediaFormat[] languageMatches = candidates
                .Where(format => string.Equals(format.Language, language, StringComparison.OrdinalIgnoreCase))
                .ToArray();
            if (languageMatches.Length > 0)
            {
                candidates = languageMatches;
            }
        }

        return candidates
            .OrderByDescending(static format => format.IsDefault)
            .ThenByDescending(static format => format.Bandwidth ?? 0)
            .FirstOrDefault();
    }

    private static MediaFormat[] SelectSubtitles(
        IReadOnlyList<MediaFormat> formats,
        string? language)
    {
        if (string.Equals(language, "None", StringComparison.OrdinalIgnoreCase))
        {
            return [];
        }

        if (string.IsNullOrWhiteSpace(language)
            || string.Equals(language, "Default", StringComparison.OrdinalIgnoreCase))
        {
            return formats.Where(static format => format.IsDefault).ToArray();
        }

        return formats
            .Where(format => string.Equals(format.Language, language, StringComparison.OrdinalIgnoreCase))
            .ToArray();
    }
}
