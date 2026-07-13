using System.Globalization;
using XDM.Core.Diagnostics;

namespace XDM.Diagnostics;

public static class TransferDiagnosticInsightBuilder
{
    public static TransferDiagnosticInsights Build(IReadOnlyList<TransferDiagnosticEvent> events)
    {
        ArgumentNullException.ThrowIfNull(events);
        Dictionary<string, string?> headers = new(StringComparer.OrdinalIgnoreCase);
        foreach (TransferDiagnosticEvent item in events.OrderByDescending(static item => item.Timestamp))
        {
            foreach ((string key, string? value) in item.Context)
            {
                if (!key.StartsWith("header.", StringComparison.Ordinal)
                    || headers.ContainsKey(key[7..]))
                {
                    continue;
                }

                headers[key[7..]] = SecretRedactor.Redact(value ?? string.Empty);
            }
        }

        TransferDiagnosticEvent[] retries = events
            .Where(static item => item.Stage == TransferDiagnosticStage.Retry)
            .OrderByDescending(static item => item.Timestamp)
            .ToArray();
        TransferSegmentDiagnostic[] segments = events
            .Where(static item => item.Context.ContainsKey("segmentIndex"))
            .GroupBy(static item => ParseInt(item.Context, "segmentIndex"))
            .Where(static group => group.Key >= 0)
            .Select(static group => CreateSegment(group.Key, group.OrderByDescending(static item => item.Timestamp).First()))
            .OrderBy(static segment => segment.Index)
            .ToArray();
        TransferDiagnosticEvent? resume = events
            .Where(static item => item.Stage == TransferDiagnosticStage.Resume)
            .OrderByDescending(static item => item.Timestamp)
            .FirstOrDefault();
        return new TransferDiagnosticInsights(
            headers,
            retries,
            segments,
            FormatResumeSummary(resume));
    }

    private static TransferSegmentDiagnostic CreateSegment(int index, TransferDiagnosticEvent item)
    {
        long start = ParseLong(item.Context, "segmentStart");
        long end = ParseLong(item.Context, "segmentEnd");
        long length = Math.Max(0, ParseLong(item.Context, "segmentLength"));
        if (length == 0 && end >= start)
        {
            length = checked(end - start + 1);
        }

        long bytes = Math.Clamp(ParseLong(item.Context, "segmentBytes"), 0, length);
        string state = item.Code switch
        {
            "XDM-TRANSFER-SEGMENT-COMPLETED" => "Complete",
            "XDM-TRANSFER-SEGMENT-RETRY" => "Retrying",
            _ => "In progress"
        };
        return new TransferSegmentDiagnostic(index, start, end, length, bytes, state, item.Timestamp);
    }

    private static string FormatResumeSummary(TransferDiagnosticEvent? resume)
    {
        if (resume is null)
        {
            return "Resume capability has not been evaluated for this transfer.";
        }

        return resume.Code switch
        {
            "XDM-TRANSFER-RESUME-VALIDATED" => "Resume is available: server identity and returned byte range were validated before append.",
            "XDM-TRANSFER-RESUME-416-VALIDATED" => "Resume validation confirmed that the partial file already contains the complete remote object.",
            "XDM-TRANSFER-SEGMENT-VALIDATED" => "Segmented resume data was range-validated and merged.",
            "XDM-TRANSFER-RESUME-REJECTED" => $"Resume is unavailable: {resume.Message}",
            "XDM-TRANSFER-RESUME-REQUEST" => "Resume validation is in progress; XDM has requested a conditional byte range.",
            _ => resume.Message
        };
    }

    private static int ParseInt(IReadOnlyDictionary<string, string?> context, string key)
        => context.TryGetValue(key, out string? value)
            && int.TryParse(value, NumberStyles.Integer, CultureInfo.InvariantCulture, out int parsed)
                ? parsed
                : -1;

    private static long ParseLong(IReadOnlyDictionary<string, string?> context, string key)
        => context.TryGetValue(key, out string? value)
            && long.TryParse(value, NumberStyles.Integer, CultureInfo.InvariantCulture, out long parsed)
                ? parsed
                : 0;
}
