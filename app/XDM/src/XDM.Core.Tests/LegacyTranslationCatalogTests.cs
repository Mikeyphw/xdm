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
    public void ResolvesSystemLanguageWithScriptRegionAwareFallback()
    {
        string directory = CreateTemporaryDirectory();
        try
        {
            File.WriteAllText(
                Path.Combine(directory, "index.txt"),
                string.Join(Environment.NewLine,
                [
                    "English=English.txt",
                    "Chinese simplified (简体中文)=Chinese simplified.txt",
                    "Chinese Traditional (繁體中文)=Chinese Traditional.txt",
                    "Traditional Chinese - Taiwan (繁體中文(台灣))=Traditional Chinese - Taiwan.txt",
                    "Serbian - Latin (Srpski (latinica))=Serbian - Latin.txt",
                    "Serbian Cyrillic (Српски (ћирилица))=Serbian Cyrillic.txt"
                ]) + Environment.NewLine);
            foreach (string fileName in new[]
            {
                "English.txt",
                "Chinese simplified.txt",
                "Chinese Traditional.txt",
                "Traditional Chinese - Taiwan.txt",
                "Serbian - Latin.txt",
                "Serbian Cyrillic.txt"
            })
            {
                File.WriteAllText(Path.Combine(directory, fileName), "MENU_PAUSE=Pause" + Environment.NewLine);
            }

            LegacyTranslationCatalog catalog = LegacyTranslationCatalog.Load(directory);
            Assert.Equal("zh-Hant", catalog.ResolveLanguage("zh-HK", CultureInfo.GetCultureInfo("en-US"), false).Id);
            Assert.Equal("zh-Hans", catalog.ResolveLanguage("zh-SG", CultureInfo.GetCultureInfo("en-US"), false).Id);
            Assert.Equal("sr-Latn", catalog.ResolveLanguage("sr-Latn-RS", CultureInfo.GetCultureInfo("en-US"), false).Id);
            Assert.Equal("sr-Cyrl", catalog.ResolveLanguage("sr-RS", CultureInfo.GetCultureInfo("en-US"), false).Id);
        }
        finally
        {
            Directory.Delete(directory, recursive: true);
        }
    }

    [Fact]
    public void LanguageIndexIncludesEveryMappedShippedLanguage()
    {
        string directory = CreateTemporaryDirectory();
        try
        {
            File.WriteAllText(
                Path.Combine(directory, "index.txt"),
                string.Join(Environment.NewLine,
                [
                    "English=English.txt",
                    "Hindi=Hindi.txt",
                    "Malagasy=Malagasy.txt"
                ]) + Environment.NewLine);
            File.WriteAllText(Path.Combine(directory, "English.txt"), "MENU_PAUSE=Pause" + Environment.NewLine);
            File.WriteAllText(Path.Combine(directory, "Hindi.txt"), "MENU_PAUSE=रोकें" + Environment.NewLine);
            File.WriteAllText(Path.Combine(directory, "Malagasy.txt"), "MENU_PAUSE=Ajanony" + Environment.NewLine);

            LegacyTranslationCatalog catalog = LegacyTranslationCatalog.Load(directory);
            Assert.Contains(catalog.Languages, static language => language.Id == "hi" && language.FileName == "Hindi.txt");
            Assert.Contains(catalog.Languages, static language => language.Id == "mg" && language.FileName == "Malagasy.txt");
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
