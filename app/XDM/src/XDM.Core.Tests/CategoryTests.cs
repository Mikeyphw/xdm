using XDM.Core.Categories;
using XDM.Core.Downloads;
using XDM.Core.Settings;

namespace XDM.Core.Tests;

public sealed class CategoryTests
{
    [Theory]
    [InlineData("archive.zip")]
    [InlineData("ARCHIVE.7Z")]
    [InlineData("backup.tar.zst")]
    public void MatchesFileNameNormalizesExtensions(string fileName)
    {
        DownloadCategory category = new(
            "archives",
            "Archives",
            [".zip", "7z", "tar.zst"],
            Path.GetTempPath());

        Assert.True(category.MatchesFileName(fileName));
    }

    [Fact]
    public void MatchesFileNameRejectsUnknownExtension()
    {
        DownloadCategory category = new(
            "documents",
            "Documents",
            ["pdf"],
            Path.GetTempPath());

        Assert.False(category.MatchesFileName("archive.zip"));
    }
    [Fact]
    public void AutoCategoryRoutingSupportsCompoundExtensionsAndDestination()
    {
        string root = Path.Combine(Path.GetTempPath(), "xdm-rem05");
        ApplicationSettings settings = ApplicationSettings.CreateDefault() with
        {
            DefaultDownloadDirectory = root,
            Categories =
            [
                new DownloadCategoryDefinition("general", "General", [], root),
                new DownloadCategoryDefinition("archives", "Archives", ["tar.gz"], Path.Combine(root, "archives"))
            ],
            DownloadBehavior = DownloadBehaviorSettings.Default with { AutoSelectCategory = true }
        };

        DownloadCategoryRoute route = DownloadCategoryRouting.Resolve(
            settings,
            new Uri("https://example.test/archive.tar.gz"),
            "general",
            root);

        Assert.Equal("archives", route.CategoryId);
        Assert.Equal(Path.Combine(root, "archives"), route.DestinationDirectory);
    }

    [Fact]
    public void PathIdentityUsesPlatformCaseSemantics()
    {
        string upper = Path.Combine(Path.GetTempPath(), "File.bin");
        string lower = Path.Combine(Path.GetTempPath(), "file.bin");
        Assert.Equal(OperatingSystem.IsWindows(), DownloadPathIdentity.Equals(upper, lower));
    }

}
