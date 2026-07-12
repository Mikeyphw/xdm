namespace XDM.Media;

internal sealed record HlsManifest(
    bool IsMaster,
    bool EndList,
    int TargetDurationSeconds,
    long MediaSequence,
    IReadOnlyList<HlsVariant> Variants,
    IReadOnlyList<HlsRendition> Renditions,
    IReadOnlyList<HlsSegment> Segments);

internal sealed record HlsVariant(
    Uri Uri,
    long? Bandwidth,
    int? Width,
    int? Height,
    double? FrameRate,
    string? Codecs,
    string? AudioGroup,
    string? SubtitleGroup,
    string? Name);

internal sealed record HlsRendition(
    string Type,
    string GroupId,
    string Name,
    string? Language,
    Uri? Uri,
    bool IsDefault,
    bool IsForced);

internal sealed record HlsSegment(
    long Sequence,
    Uri Uri,
    double DurationSeconds,
    long? ByteRangeLength,
    long? ByteRangeOffset,
    HlsEncryptionKey? Key,
    HlsInitializationMap? InitializationMap,
    bool Discontinuity);

internal sealed record HlsEncryptionKey(string Method, Uri Uri, byte[]? InitializationVector);

internal sealed record HlsInitializationMap(Uri Uri, long? ByteRangeLength, long? ByteRangeOffset);
