using XDM.BrowserIntegration;

namespace XDM.BrowserMedia.Tests;

public sealed class BrowserCaptureRuleEvaluatorTests
{
    private static readonly BrowserCaptureRequest BaseRequest = new(
        new Uri("https://downloads.example.test/archive.zip"),
        FileName: "archive.zip",
        MimeType: "application/zip",
        FileSize: 8 * 1024 * 1024);

    [Fact]
    public void AppliesSizeMimeExtensionAndSiteRules()
    {
        Assert.Equal("below_minimum_size", Evaluate(new BrowserCaptureRules(MinimumSizeBytes: 9 * 1024 * 1024)).Reason);
        Assert.Equal("mime_blocked", Evaluate(new BrowserCaptureRules(BlockedMimeTypes: ["application/*"])).Reason);
        Assert.Equal("mime_not_allowed", Evaluate(new BrowserCaptureRules(AllowedMimeTypes: ["video/*"])).Reason);
        Assert.Equal("extension_blocked", Evaluate(new BrowserCaptureRules(BlockedExtensions: [".zip"])).Reason);
        Assert.Equal("extension_not_allowed", Evaluate(new BrowserCaptureRules(AllowedExtensions: ["pdf"])).Reason);
        Assert.Equal("site_excluded", Evaluate(new BrowserCaptureRules(ExcludedSites: ["example.test"])).Reason);
        Assert.Equal("site_not_included", Evaluate(new BrowserCaptureRules(IncludedSites: ["other.test"])).Reason);
    }

    [Fact]
    public void AppliesTemporaryDisableAndIncognitoPolicy()
    {
        DateTimeOffset now = new(2026, 7, 11, 12, 0, 0, TimeSpan.Zero);
        BrowserCaptureRuleDecision disabled = BrowserCaptureRuleEvaluator.Evaluate(
            BaseRequest,
            new BrowserCaptureRules(DisabledUntilUtc: now.AddMinutes(5)),
            now);
        BrowserCaptureRuleDecision incognito = BrowserCaptureRuleEvaluator.Evaluate(
            BaseRequest with { IsIncognito = true },
            new BrowserCaptureRules(CaptureIncognito: false),
            now);

        Assert.Equal("temporarily_disabled", disabled.Reason);
        Assert.Equal("incognito_disabled", incognito.Reason);
    }

    [Fact]
    public void ManualCaptureBypassesContentRulesButNotIncognitoPolicy()
    {
        BrowserCaptureRequest manual = BaseRequest with
        {
            Operation = "context",
            BypassRules = true
        };
        BrowserCaptureRules strict = new(
            MinimumSizeBytes: long.MaxValue,
            BlockedMimeTypes: ["application/zip"],
            ExcludedSites: ["example.test"]);

        Assert.Equal("manual_capture", BrowserCaptureRuleEvaluator.Evaluate(manual, strict).Reason);
        Assert.Equal(
            "incognito_disabled",
            BrowserCaptureRuleEvaluator.Evaluate(manual with { IsIncognito = true }, strict).Reason);
    }

    [Fact]
    public void AcceptsMatchingIncludedSubdomainAndWildcardMime()
    {
        BrowserCaptureRules rules = new(
            MinimumSizeBytes: 1,
            AllowedMimeTypes: ["application/*"],
            AllowedExtensions: ["zip"],
            IncludedSites: ["example.test"]);

        Assert.True(Evaluate(rules).Accepted);
    }

    private static BrowserCaptureRuleDecision Evaluate(BrowserCaptureRules rules)
        => BrowserCaptureRuleEvaluator.Evaluate(BaseRequest, rules);
}
