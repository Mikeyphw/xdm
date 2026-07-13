using XDM.Core.Diagnostics;

namespace XDM.Diagnostics.Tests;

public sealed class TransferDiagnosticInsightBuilderTests
{
    [Fact]
    public void BuildExtractsRedactedHeadersRetrySegmentAndResumeReason()
    {
        DateTimeOffset now = DateTimeOffset.UtcNow;
        TransferDiagnosticEvent[] events =
        [
            new(
                now,
                "download-1",
                TransferDiagnosticStage.Http,
                TransferDiagnosticSeverity.Information,
                "XDM-TRANSFER-HTTP-RESPONSE",
                "HTTP response",
                new Dictionary<string, string?>
                {
                    ["header.Content-Type"] = "application/octet-stream",
                    ["header.Location"] = "https://example.test/file?token=secret"
                }),
            new(
                now.AddSeconds(1),
                "download-1",
                TransferDiagnosticStage.Retry,
                TransferDiagnosticSeverity.Warning,
                "XDM-TRANSFER-SEGMENT-RETRY",
                "Retrying segment",
                new Dictionary<string, string?>
                {
                    ["segmentIndex"] = "2",
                    ["segmentStart"] = "200",
                    ["segmentEnd"] = "299",
                    ["attempt"] = "1"
                }),
            new(
                now.AddSeconds(2),
                "download-1",
                TransferDiagnosticStage.Http,
                TransferDiagnosticSeverity.Information,
                "XDM-TRANSFER-SEGMENT-COMPLETED",
                "Segment completed",
                new Dictionary<string, string?>
                {
                    ["segmentIndex"] = "2",
                    ["segmentStart"] = "200",
                    ["segmentEnd"] = "299",
                    ["segmentLength"] = "100",
                    ["segmentBytes"] = "100"
                }),
            new(
                now.AddSeconds(3),
                "download-1",
                TransferDiagnosticStage.Resume,
                TransferDiagnosticSeverity.Warning,
                "XDM-TRANSFER-RESUME-REJECTED",
                "The server ignored the requested range.",
                new Dictionary<string, string?>())
        ];

        TransferDiagnosticInsights result = TransferDiagnosticInsightBuilder.Build(events);

        Assert.Equal("application/octet-stream", result.ResponseHeaders["Content-Type"]);
        Assert.DoesNotContain("secret", result.ResponseHeaders["Location"], StringComparison.Ordinal);
        Assert.Single(result.RetryHistory);
        TransferSegmentDiagnostic segment = Assert.Single(result.Segments);
        Assert.Equal(2, segment.Index);
        Assert.Equal("Complete", segment.State);
        Assert.Equal(1d, segment.Progress);
        Assert.Contains("unavailable", result.ResumeAvailabilitySummary, StringComparison.OrdinalIgnoreCase);
    }
}
