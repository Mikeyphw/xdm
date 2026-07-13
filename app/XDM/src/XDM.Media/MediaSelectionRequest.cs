namespace XDM.Media;

public sealed record MediaSelectionRequest(
    int? MaximumHeight = null,
    bool AudioOnly = false,
    bool PreferSmallest = false,
    string? AudioLanguage = null,
    string? SubtitleLanguage = "Default");
