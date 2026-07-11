using XDM.Core.Categories;

namespace XDM.Core.Tests;

public sealed class CategoryTests
{
    [Theory]
    [InlineData("archive.zip")]
    [InlineData("ARCHIVE.7Z")]
    [InlineData("backup.tar.zst")]
    public void MatchesFileName_normalizes_extensions(string fileName)
    {
        DownloadCategory category = new(
            "archives",
            "Archives",
            [".zip", "7z", "tar.zst"],
            Path.GetTempPath());

        Assert.True(category.MatchesFileName(fileName));
    }

    [Fact]
    public void MatchesFileName_rejects_unknown_extension()
    {
        DownloadCategory category = new(
            "documents",
            "Documents",
            ["pdf"],
            Path.GetTempPath());

        Assert.False(category.MatchesFileName("archive.zip"));
    }
}
