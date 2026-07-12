using System.Globalization;
using XDM.Core.Localization;

namespace XDM.Core.Tests;

public sealed class LegacyTranslationCatalogTests
{
    [Fact]
    public void LoadsLegacyPacksWithEnglishFallbackAndRtlMetadata()
    {
        string directory = CreateTemporaryDirectory();
        try
        {
            File.WriteAllText(
                Path.Combine(directory, "index.txt"),
                "English=English.txt\nArabic (العربية)=Arabic.txt\n");
            File.WriteAllText(
                Path.Combine(directory, "English.txt"),
                "MENU_PAUSE=Pause\nMENU_RESUME=Resume\n");
            File.WriteAllText(Path.Combine(directory, "Arabic.txt"), "MENU_PAUSE=توقف\n");

            LegacyTranslationCatalog catalog = LegacyTranslationCatalog.Load(directory);
            LanguageDefinition arabic = catalog.ResolveLanguage("ar", CultureInfo.GetCultureInfo("en-US"), false);

            Assert.True(arabic.IsRightToLeft);
            Assert.Equal("توقف", catalog.GetString("ar", "MENU_PAUSE", "Pause"));
            Assert.Equal("Resume", catalog.GetString("ar", "MENU_RESUME", "Resume"));
            Assert.Equal("MENU_PAUSE", catalog.FindLegacyKey("Pause"));
            Assert.Equal("en", catalog.ResolveLanguage("ar", CultureInfo.InvariantCulture, true).Id);
        }
        finally
        {
            Directory.Delete(directory, recursive: true);
        }
    }

    [Fact]
    public void RejectsMalformedAndOversizedEntries()
    {
        string oversizedKey = new('K', 161);
        Dictionary<string, string> values = LegacyTranslationCatalog.Parse(
        [
            "# comment",
            "missing separator",
            "GOOD=value=with=equals",
            $"{oversizedKey}=ignored",
        ]);

        Assert.Equal("value=with=equals", Assert.Single(values).Value);
    }

    [Theory]
    [InlineData("Portuguese Brazil", "pt-BR")]
    [InlineData("Portuguese Brazil.txt", "pt-BR")]
    [InlineData("ar", "ar")]
    [InlineData("unknown language", "en")]
    public void NormalizesLegacyLanguageIdentifiers(string input, string expected)
        => Assert.Equal(expected, LegacyLanguageIndex.NormalizeIdentifier(input));

    private static string CreateTemporaryDirectory()
    {
        string path = Path.Combine(Path.GetTempPath(), $"xdm-localization-{Guid.NewGuid():N}");
        Directory.CreateDirectory(path);
        return path;
    }
}
