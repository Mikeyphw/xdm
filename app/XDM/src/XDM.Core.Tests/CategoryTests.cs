using XDM.Core.Categories;

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
}
