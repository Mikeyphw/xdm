using System.Globalization;
using System.Text.RegularExpressions;
using System.Xml;
using System.Xml.Linq;

namespace XDM.Media;

internal static partial class DashManifestParser
{
    private const int MaximumRepresentations = 10_000;
    private const int MaximumSegmentsPerRepresentation = 1_000_000;

    public static DashManifest Parse(Uri manifestUri, string content)
    {
        ArgumentNullException.ThrowIfNull(manifestUri);
        ArgumentNullException.ThrowIfNull(content);
        XmlReaderSettings settings = new()
        {
            DtdProcessing = DtdProcessing.Prohibit,
            XmlResolver = null,
            MaxCharactersInDocument = MediaHttp.MaximumManifestBytes
        };
        XDocument document;
        try
        {
            using StringReader textReader = new(content);
            using XmlReader xmlReader = XmlReader.Create(textReader, settings);
            document = XDocument.Load(xmlReader, LoadOptions.None);
        }
        catch (XmlException exception)
        {
            throw new InvalidDataException("The DASH manifest is not valid XML.", exception);
        }

        XElement root = document.Root ?? throw new InvalidDataException("The DASH manifest is empty.");
        if (!root.Name.LocalName.Equals("MPD", StringComparison.Ordinal))
        {
            throw new InvalidDataException("The response is not a DASH MPD manifest.");
        }

        bool dynamic = string.Equals(Attribute(root, "type"), "dynamic", StringComparison.OrdinalIgnoreCase);
        TimeSpan? duration = ParseDuration(Attribute(root, "mediaPresentationDuration"));
        DateTimeOffset? availabilityStartTime = ParseDateTime(Attribute(root, "availabilityStartTime"));
        TimeSpan? timeShiftBufferDepth = ParseDuration(Attribute(root, "timeShiftBufferDepth"));
        TimeSpan minimumUpdate = ParseDuration(Attribute(root, "minimumUpdatePeriod")) ?? TimeSpan.FromSeconds(5);
        minimumUpdate = TimeSpan.FromSeconds(Math.Clamp(minimumUpdate.TotalSeconds, 1, 60));
        Uri rootBase = ResolveBase(manifestUri, FirstChildValue(root, "BaseURL"));
        List<DashRepresentation> representations = [];
        foreach (XElement period in Children(root, "Period"))
        {
            TimeSpan? periodDuration = ParseDuration(Attribute(period, "duration")) ?? duration;
            Uri periodBase = ResolveBase(rootBase, FirstChildValue(period, "BaseURL"));
            foreach (XElement adaptation in Children(period, "AdaptationSet"))
            {
                Uri adaptationBase = ResolveBase(periodBase, FirstChildValue(adaptation, "BaseURL"));
                string? adaptationMime = Attribute(adaptation, "mimeType");
                string? adaptationContentType = Attribute(adaptation, "contentType");
                string? adaptationCodecs = Attribute(adaptation, "codecs");
                string? language = Attribute(adaptation, "lang");
                DashSegmentTemplate? adaptationTemplate = ParseTemplate(FirstChild(adaptation, "SegmentTemplate"));
                DashSegmentList? adaptationList = ParseList(adaptationBase, FirstChild(adaptation, "SegmentList"));
                foreach (XElement representation in Children(adaptation, "Representation"))
                {
                    if (representations.Count >= MaximumRepresentations)
                    {
                        throw new InvalidDataException("DASH manifest exceeds the supported representation count.");
                    }

                    string id = Attribute(representation, "id")
                        ?? throw new InvalidDataException("DASH representation is missing an id.");
                    Uri representationBase = ResolveBase(adaptationBase, FirstChildValue(representation, "BaseURL"));
                    string? mime = Attribute(representation, "mimeType") ?? adaptationMime;
                    string? contentType = Attribute(representation, "contentType") ?? adaptationContentType;
                    MediaStreamKind kind = ResolveKind(contentType, mime);
                    DashSegmentTemplate? template = ParseTemplate(FirstChild(representation, "SegmentTemplate")) ?? adaptationTemplate;
                    DashSegmentList? list = ParseList(representationBase, FirstChild(representation, "SegmentList")) ?? adaptationList;
                    if (template is null && list is null && FirstChildValue(representation, "BaseURL") is not null)
                    {
                        list = new DashSegmentList(null, [representationBase]);
                    }

                    representations.Add(new DashRepresentation(
                        id,
                        kind,
                        representationBase,
                        mime,
                        Attribute(representation, "codecs") ?? adaptationCodecs,
                        ParseLong(Attribute(representation, "bandwidth")),
                        ParseInt(Attribute(representation, "width")),
                        ParseInt(Attribute(representation, "height")),
                        ParseFrameRate(Attribute(representation, "frameRate")),
                        language,
                        Attribute(representation, "label") ?? Attribute(adaptation, "label"),
                        periodDuration,
                        template,
                        list));
                }
            }
        }

        if (representations.Count == 0)
        {
            throw new InvalidDataException("DASH manifest does not contain usable representations.");
        }

        return new DashManifest(
            dynamic,
            minimumUpdate,
            duration,
            availabilityStartTime,
            timeShiftBufferDepth,
            representations);
    }

    public static List<DashSegmentReference> BuildSegments(
        DashRepresentation representation,
        DashManifest manifest,
        DateTimeOffset nowUtc)
    {
        ArgumentNullException.ThrowIfNull(representation);
        if (representation.SegmentList is DashSegmentList list)
        {
            List<DashSegmentReference> references = [];
            if (list.Initialization is not null)
            {
                references.Add(new DashSegmentReference("init", list.Initialization, true));
            }

            references.AddRange(list.SegmentUris.Select((uri, index) =>
                new DashSegmentReference($"segment-{index:D10}", uri, false)));
            return references;
        }

        DashSegmentTemplate template = representation.SegmentTemplate
            ?? throw new NotSupportedException($"DASH representation '{representation.Id}' has no supported segment addressing mode.");
        List<DashSegmentReference> segments = [];
        if (!string.IsNullOrWhiteSpace(template.Initialization))
        {
            string init = ExpandTemplate(template.Initialization, representation, template.StartNumber, 0);
            segments.Add(new DashSegmentReference("init", Resolve(representation.BaseUri, init), true));
        }

        long number = template.StartNumber;
        if (template.Timeline.Count > 0)
        {
            long currentTime = 0;
            foreach (DashTimelineEntry entry in template.Timeline)
            {
                if (entry.Time is long explicitTime)
                {
                    currentTime = explicitTime;
                }

                int repeat = entry.Repeat;
                if (repeat < 0)
                {
                    repeat = ResolveOpenRepeat(entry.Duration, currentTime, representation.PeriodDuration, template.Timescale, manifest.IsDynamic);
                }

                for (int index = 0; index <= repeat; index++)
                {
                    AddTemplateSegment(segments, template, representation, number++, currentTime);
                    currentTime = checked(currentTime + entry.Duration);
                }
            }
        }
        else if (template.Duration is long durationUnits)
        {
            int count = ResolveDurationSegmentCount(
                durationUnits,
                template.Timescale,
                representation.PeriodDuration,
                manifest,
                nowUtc);
            int bufferCount = ResolveDynamicBufferSegmentCount(durationUnits, template.Timescale, manifest);
            long firstNumber = manifest.IsDynamic
                ? Math.Max(template.StartNumber, template.StartNumber + count - bufferCount)
                : template.StartNumber;
            for (long currentNumber = firstNumber; currentNumber < template.StartNumber + count; currentNumber++)
            {
                long time = checked((currentNumber - template.StartNumber) * durationUnits);
                AddTemplateSegment(segments, template, representation, currentNumber, time);
            }
        }
        else
        {
            throw new NotSupportedException($"DASH representation '{representation.Id}' has an unsupported SegmentTemplate.");
        }

        return segments;
    }

    private static void AddTemplateSegment(
        List<DashSegmentReference> segments,
        DashSegmentTemplate template,
        DashRepresentation representation,
        long number,
        long time)
    {
        if (segments.Count >= MaximumSegmentsPerRepresentation)
        {
            throw new InvalidDataException("DASH representation exceeds the supported segment count.");
        }

        string media = ExpandTemplate(template.Media, representation, number, time);
        segments.Add(new DashSegmentReference(
            $"segment-{number:D20}-{time:D20}",
            Resolve(representation.BaseUri, media),
            false));
    }

    private static DashSegmentTemplate? ParseTemplate(XElement? element)
    {
        if (element is null)
        {
            return null;
        }

        string media = Attribute(element, "media")
            ?? throw new InvalidDataException("DASH SegmentTemplate is missing the media pattern.");
        long timescale = ParseLong(Attribute(element, "timescale")) ?? 1;
        if (timescale <= 0)
        {
            throw new InvalidDataException("DASH SegmentTemplate timescale must be positive.");
        }

        List<DashTimelineEntry> timeline = [];
        XElement? timelineElement = FirstChild(element, "SegmentTimeline");
        if (timelineElement is not null)
        {
            foreach (XElement segment in Children(timelineElement, "S"))
            {
                long duration = ParseLong(Attribute(segment, "d"))
                    ?? throw new InvalidDataException("DASH timeline segment is missing duration.");
                if (duration <= 0)
                {
                    throw new InvalidDataException("DASH timeline duration must be positive.");
                }

                timeline.Add(new DashTimelineEntry(
                    ParseLong(Attribute(segment, "t")),
                    duration,
                    ParseInt(Attribute(segment, "r")) ?? 0));
            }
        }

        return new DashSegmentTemplate(
            Attribute(element, "initialization"),
            media,
            ParseLong(Attribute(element, "startNumber")) ?? 1,
            timescale,
            ParseLong(Attribute(element, "duration")),
            timeline);
    }

    private static DashSegmentList? ParseList(Uri baseUri, XElement? element)
    {
        if (element is null)
        {
            return null;
        }

        XElement? initializationElement = FirstChild(element, "Initialization");
        string? initializationSource = initializationElement is null ? null : Attribute(initializationElement, "sourceURL");
        Uri? initialization = initializationSource is null ? null : Resolve(baseUri, initializationSource);
        Uri[] segmentUris = Children(element, "SegmentURL")
            .Select(segment => Attribute(segment, "media"))
            .Where(static media => !string.IsNullOrWhiteSpace(media))
            .Select(media => Resolve(baseUri, media!))
            .ToArray();
        return new DashSegmentList(initialization, segmentUris);
    }

    private static int ResolveOpenRepeat(
        long duration,
        long currentTime,
        TimeSpan? periodDuration,
        long timescale,
        bool dynamic)
    {
        if (periodDuration is TimeSpan total)
        {
            long totalUnits = checked((long)Math.Ceiling(total.TotalSeconds * timescale));
            long remaining = Math.Max(0, totalUnits - currentTime);
            return Math.Max(0, checked((int)Math.Ceiling((double)remaining / duration)) - 1);
        }

        return dynamic ? 0 : throw new NotSupportedException("An open DASH timeline repeat requires a period duration.");
    }

    private static int ResolveDurationSegmentCount(
        long duration,
        long timescale,
        TimeSpan? periodDuration,
        DashManifest manifest,
        DateTimeOffset nowUtc)
    {
        if (periodDuration is TimeSpan total)
        {
            return Math.Max(1, checked((int)Math.Ceiling(total.TotalSeconds * timescale / duration)));
        }

        if (manifest.IsDynamic)
        {
            TimeSpan elapsed = manifest.AvailabilityStartTime is DateTimeOffset start
                ? nowUtc - start
                : TimeSpan.FromSeconds((double)duration / timescale);
            double seconds = Math.Max((double)duration / timescale, elapsed.TotalSeconds);
            long estimate = checked((long)Math.Ceiling(seconds * timescale / duration));
            return checked((int)Math.Clamp(estimate, 1, MaximumSegmentsPerRepresentation));
        }

        throw new NotSupportedException("A duration-based DASH SegmentTemplate requires a period duration.");
    }

    private static int ResolveDynamicBufferSegmentCount(
        long duration,
        long timescale,
        DashManifest manifest)
    {
        if (!manifest.IsDynamic)
        {
            return MaximumSegmentsPerRepresentation;
        }

        TimeSpan depth = manifest.TimeShiftBufferDepth ?? TimeSpan.FromMinutes(10);
        int count = checked((int)Math.Ceiling(depth.TotalSeconds * timescale / duration));
        return Math.Clamp(count, 1, 10_000);
    }

    private static string ExpandTemplate(
        string template,
        DashRepresentation representation,
        long number,
        long time)
    {
        string value = template
            .Replace("$RepresentationID$", representation.Id, StringComparison.Ordinal)
            .Replace("$Bandwidth$", (representation.Bandwidth ?? 0).ToString(CultureInfo.InvariantCulture), StringComparison.Ordinal)
            .Replace("$Time$", time.ToString(CultureInfo.InvariantCulture), StringComparison.Ordinal);
        value = NumberTemplateRegex().Replace(value, match =>
        {
            string widthText = match.Groups[1].Value;
            if (widthText.Length == 0)
            {
                return number.ToString(CultureInfo.InvariantCulture);
            }

            int width = int.Parse(widthText, NumberStyles.None, CultureInfo.InvariantCulture);
            return number.ToString($"D{width}", CultureInfo.InvariantCulture);
        });
        return value.Replace("$$", "$", StringComparison.Ordinal);
    }

    private static MediaStreamKind ResolveKind(string? contentType, string? mime)
    {
        string value = contentType ?? mime ?? string.Empty;
        if (value.Contains("video", StringComparison.OrdinalIgnoreCase))
        {
            return MediaStreamKind.Video;
        }

        if (value.Contains("audio", StringComparison.OrdinalIgnoreCase))
        {
            return MediaStreamKind.Audio;
        }

        if (value.Contains("text", StringComparison.OrdinalIgnoreCase)
            || value.Contains("subtitle", StringComparison.OrdinalIgnoreCase)
            || value.Contains("application/ttml", StringComparison.OrdinalIgnoreCase))
        {
            return MediaStreamKind.Subtitle;
        }

        return MediaStreamKind.Muxed;
    }

    private static Uri ResolveBase(Uri parent, string? child)
        => string.IsNullOrWhiteSpace(child) ? parent : Resolve(parent, child);

    private static Uri Resolve(Uri baseUri, string value)
    {
        if (!Uri.TryCreate(baseUri, value, out Uri? uri) || uri.Scheme is not ("http" or "https"))
        {
            throw new InvalidDataException("DASH manifest contains an invalid or unsupported URI.");
        }

        return uri;
    }

    private static string? FirstChildValue(XElement parent, string localName)
        => FirstChild(parent, localName)?.Value.Trim();

    private static XElement? FirstChild(XElement parent, string localName)
        => parent.Elements().FirstOrDefault(element => element.Name.LocalName.Equals(localName, StringComparison.Ordinal));

    private static IEnumerable<XElement> Children(XElement parent, string localName)
        => parent.Elements().Where(element => element.Name.LocalName.Equals(localName, StringComparison.Ordinal));

    private static string? Attribute(XElement element, string localName)
        => element.Attributes().FirstOrDefault(attribute => attribute.Name.LocalName.Equals(localName, StringComparison.Ordinal))?.Value;

    private static TimeSpan? ParseDuration(string? value)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            return null;
        }

        try
        {
            return XmlConvert.ToTimeSpan(value);
        }
        catch (FormatException exception)
        {
            throw new InvalidDataException("DASH duration is invalid.", exception);
        }
    }

    private static DateTimeOffset? ParseDateTime(string? value)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            return null;
        }

        if (DateTimeOffset.TryParse(
            value,
            CultureInfo.InvariantCulture,
            DateTimeStyles.AssumeUniversal | DateTimeStyles.AdjustToUniversal,
            out DateTimeOffset parsed))
        {
            return parsed;
        }

        throw new InvalidDataException("DASH date/time value is invalid.");
    }

    private static long? ParseLong(string? value)
        => long.TryParse(value, NumberStyles.Integer, CultureInfo.InvariantCulture, out long parsed) ? parsed : null;

    private static int? ParseInt(string? value)
        => int.TryParse(value, NumberStyles.Integer, CultureInfo.InvariantCulture, out int parsed) ? parsed : null;

    private static double? ParseFrameRate(string? value)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            return null;
        }

        string[] parts = value.Split('/', 2, StringSplitOptions.TrimEntries);
        if (parts.Length == 2
            && double.TryParse(parts[0], NumberStyles.Float, CultureInfo.InvariantCulture, out double numerator)
            && double.TryParse(parts[1], NumberStyles.Float, CultureInfo.InvariantCulture, out double denominator)
            && denominator != 0)
        {
            return numerator / denominator;
        }

        return double.TryParse(value, NumberStyles.Float, CultureInfo.InvariantCulture, out double parsed)
            ? parsed
            : null;
    }

    [GeneratedRegex("\\$Number(?:%0([0-9]+)d)?\\$", RegexOptions.CultureInvariant)]
    private static partial Regex NumberTemplateRegex();
}
