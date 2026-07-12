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
