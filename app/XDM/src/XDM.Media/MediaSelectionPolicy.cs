namespace XDM.Media;

public static class MediaSelectionPolicy
{
    public static MediaSelectionResult Select(MediaCatalog catalog, MediaSelectionRequest request)
    {
        ArgumentNullException.ThrowIfNull(catalog);
        ArgumentNullException.ThrowIfNull(request);

        MediaFormat? video = SelectVideo(catalog.VideoFormats, request);
        MediaFormat? audio = SelectAudio(catalog.AudioFormats, video, request.AudioLanguage);
        MediaFormat[] subtitles = SelectSubtitles(catalog.SubtitleFormats, video, request.SubtitleLanguage);
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

        IEnumerable<MediaFormat> eligible = formats;
        if (request.MaximumHeight is int height)
        {
            eligible = eligible.Where(format => format.Height is int formatHeight && formatHeight <= height);
        }

        MediaFormat[] candidates = eligible.ToArray();
        if (request.PreferSmallest)
        {
            return candidates
                .OrderBy(static format => format.Height ?? int.MaxValue)
                .ThenBy(static format => format.Bandwidth ?? long.MaxValue)
                .FirstOrDefault();
        }

        return candidates
            .OrderByDescending(static format => format.IsDefault)
            .ThenBy(static format => IsNonPrimaryRole(format.Role))
            .ThenByDescending(static format => format.Height ?? 0)
            .ThenByDescending(static format => format.Bandwidth ?? 0)
            .FirstOrDefault();
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

        IEnumerable<MediaFormat> candidates = FilterByAssociatedGroup(
            formats,
            video?.AudioGroupId,
            static format => format.AudioGroupId);
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
            .ThenBy(static format => IsNonPrimaryRole(format.Role))
            .ThenByDescending(static format => format.Bandwidth ?? 0)
            .FirstOrDefault();
    }

    private static MediaFormat[] SelectSubtitles(
        IReadOnlyList<MediaFormat> formats,
        MediaFormat? video,
        string? language)
    {
        if (string.Equals(language, "None", StringComparison.OrdinalIgnoreCase))
        {
            return [];
        }

        IEnumerable<MediaFormat> candidates = FilterByAssociatedGroup(
            formats,
            video?.SubtitleGroupId,
            static format => format.SubtitleGroupId);

        if (string.IsNullOrWhiteSpace(language)
            || string.Equals(language, "Default", StringComparison.OrdinalIgnoreCase))
        {
            return candidates
                .Where(static format => format.IsDefault)
                .OrderBy(static format => IsNonPrimaryRole(format.Role))
                .ToArray();
        }

        return candidates
            .Where(format => string.Equals(format.Language, language, StringComparison.OrdinalIgnoreCase))
            .OrderByDescending(static format => format.IsDefault)
            .ThenBy(static format => IsNonPrimaryRole(format.Role))
            .ToArray();
    }

    private static IEnumerable<MediaFormat> FilterByAssociatedGroup(
        IReadOnlyList<MediaFormat> formats,
        string? requiredGroup,
        Func<MediaFormat, string?> groupSelector)
    {
        if (string.IsNullOrWhiteSpace(requiredGroup))
        {
            return formats;
        }

        MediaFormat[] matching = formats
            .Where(format => string.Equals(groupSelector(format), requiredGroup, StringComparison.Ordinal))
            .ToArray();
        return matching.Length > 0 ? matching : formats;
    }

    private static bool IsNonPrimaryRole(string? role)
        => role is not null
            && (role.Equals("commentary", StringComparison.OrdinalIgnoreCase)
                || role.Equals("alternate", StringComparison.OrdinalIgnoreCase)
                || role.Equals("dub", StringComparison.OrdinalIgnoreCase));
}
