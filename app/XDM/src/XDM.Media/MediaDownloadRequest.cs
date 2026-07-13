namespace XDM.Media;

public sealed record MediaDownloadRequest(
    Uri Source,
    string DestinationPath,
    string? VideoFormatId = null,
    string? AudioFormatId = null,
    IReadOnlyList<string>? SubtitleFormatIds = null,
    TimeSpan? LiveDuration = null,
    MediaRequestMetadata? Metadata = null,
    bool KeepPartialFiles = false,
    long? MaximumBytes = null)
{
    public IReadOnlyList<string> SubtitleIds => SubtitleFormatIds ?? [];
}
