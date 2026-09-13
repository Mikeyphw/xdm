namespace XDM.Media;

internal sealed record DashManifest(
    bool IsDynamic,
    TimeSpan MinimumUpdatePeriod,
    TimeSpan? Duration,
    DateTimeOffset? AvailabilityStartTime,
    TimeSpan? TimeShiftBufferDepth,
    IReadOnlyList<DashRepresentation> Representations);

internal sealed record DashRepresentation(
    string Id,
    string ScopedId,
    MediaStreamKind StreamKind,
    Uri BaseUri,
    string? Container,
    string? Codecs,
    long? Bandwidth,
    int? Width,
    int? Height,
    double? FrameRate,
    string? Language,
    string? Name,
    TimeSpan? PeriodDuration,
    string PeriodId,
    int PeriodIndex,
    string AdaptationSetId,
    int AdaptationSetIndex,
    string? Role,
    bool IsDefault,
    bool IsEncrypted,
    DashSegmentTemplate? SegmentTemplate,
    DashSegmentList? SegmentList);

internal sealed record DashSegmentTemplate(
    string? Initialization,
    string Media,
    long StartNumber,
    long Timescale,
    long? Duration,
    IReadOnlyList<DashTimelineEntry> Timeline);

internal sealed record DashTimelineEntry(long? Time, long Duration, int Repeat);

internal sealed record DashSegmentList(
    Uri? Initialization,
    IReadOnlyList<Uri> SegmentUris);

internal sealed record DashSegmentReference(string Id, Uri Uri, bool IsInitialization);
