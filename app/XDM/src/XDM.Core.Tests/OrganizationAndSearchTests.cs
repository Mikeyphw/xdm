using XDM.Core.Downloads;
using XDM.Core.Settings;

namespace XDM.Core.Tests;

public sealed class OrganizationAndSearchTests
{
    [Fact]
    public void DestinationRuleMatchesHostAndExtension()
    {
        DestinationRuleDefinition rule = new(
            "packages",
            "Packages",
            true,
            0,
            "/tmp/packages",
            HostSuffix: "github.com",
            Extensions: ["zip", "tar"]);

        Assert.True(rule.Matches(new Uri("https://releases.github.com/tool.zip"), "tool.zip"));
        Assert.False(rule.Matches(new Uri("https://example.test/tool.zip"), "tool.zip"));
        Assert.False(rule.Matches(new Uri("https://releases.github.com/tool.exe"), "tool.exe"));
    }

    [Fact]
    public void SearchExpressionSupportsTagsSitesSizesAndStateFlags()
    {
        DownloadSearchDocument document = new(
            "archive.zip",
            new Uri("https://cdn.example.test/archive.zip"),
            "/downloads/archive.zip",
            DownloadState.Completed,
            "night",
            "archives",
            ["release", "work"],
            2L * 1024 * 1024 * 1024,
            false,
            false,
            true);

        Assert.True(DownloadSearchExpression.Matches(
            "tag:release site:example.test size:>1GB duplicate:true archived:false",
            document));
        Assert.False(DownloadSearchExpression.Matches("missing:true", document));
        Assert.False(DownloadSearchExpression.Matches("queue:day", document));
    }

    [Fact]
    public void SearchExpressionRejectsMalformedBooleanAndNormalizesUrlIdentity()
    {
        DownloadSearchDocument document = new(
            "file.bin",
            new Uri("https://example.test:443/file.bin#fragment"),
            "/downloads/file.bin",
            DownloadState.Completed,
            "default",
            null,
            [],
            128,
            false,
            false,
            false);

        Assert.False(DownloadSearchExpression.Matches("archived:maybe", document));
        Assert.Equal(
            "https://example.test/file.bin",
            DownloadMetadata.NormalizeSourceIdentity(document.Source));
    }

    [Fact]
    public void OrganizationSettingsNormalizeTagsRulesAndSearches()
    {
        OrganizationSettings settings = new(
            DuplicateUrlBehavior.FocusExisting,
            true,
            [
                new DestinationRuleDefinition(
                    " rule ",
                    " Rule ",
                    true,
                    -1,
                    " /tmp/files ",
                    Tags: ["Work", "work", " Release "])
            ],
            [new SavedSearchDefinition(" recent ", " Recent ", " status:completed ")]);

        OrganizationSettings normalized = settings.Normalize();

        DestinationRuleDefinition rule = Assert.Single(normalized.DestinationRules);
        Assert.Equal("rule", rule.Id);
        Assert.Equal(0, rule.Priority);
        Assert.Collection(
            Assert.IsAssignableFrom<IReadOnlyList<string>>(rule.Tags),
            static tag => Assert.Equal("Work", tag),
            static tag => Assert.Equal("Release", tag));
        Assert.Equal("status:completed", Assert.Single(normalized.SavedSearches).Query);
    }
}
