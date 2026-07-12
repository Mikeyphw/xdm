using System.Globalization;

namespace XDM.Core.Localization;

public static class LegacyLanguageIndex
{
    private const int MaximumLanguages = 128;

    private static readonly Dictionary<string, string> CultureByFileName =
        new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase)
        {
            ["English.txt"] = "en",
            ["Arabic.txt"] = "ar",
            ["Chinese simplified.txt"] = "zh-Hans",
            ["Chinese Traditional.txt"] = "zh-Hant",
            ["Traditional Chinese - Taiwan.txt"] = "zh-TW",
            ["Czech.txt"] = "cs",
            ["Farsi-Persian.txt"] = "fa",
            ["French.txt"] = "fr",
            ["German.txt"] = "de",
            ["Hindi.txt"] = "hi",
            ["Hungarian.txt"] = "hu",
            ["Indonesian.txt"] = "id",
            ["Italian.txt"] = "it",
            ["Korea.txt"] = "ko",
            ["Malayalam.txt"] = "ml",
            ["Nepali.txt"] = "ne",
            ["Polish.txt"] = "pl",
            ["Portuguese Brazil.txt"] = "pt-BR",
            ["Romanian.txt"] = "ro",
            ["Russian.txt"] = "ru",
            ["Serbian - Latin.txt"] = "sr-Latn",
            ["Serbian Cyrillic.txt"] = "sr-Cyrl",
            ["Spanish.txt"] = "es",
            ["Turkish.txt"] = "tr",
            ["Ukrainian.txt"] = "uk",
            ["Vietnamese.txt"] = "vi",
            ["Malagasy.txt"] = "mg",
        };

    public static IReadOnlyList<LanguageDefinition> Parse(IEnumerable<string> lines)
    {
        ArgumentNullException.ThrowIfNull(lines);
        List<LanguageDefinition> languages = [];
        HashSet<string> identifiers = new(StringComparer.OrdinalIgnoreCase);

        foreach (string rawLine in lines.Take(MaximumLanguages))
        {
            string line = rawLine.Trim().TrimStart('\uFEFF');
            if (line.Length == 0 || line.StartsWith('#') || line.StartsWith(';'))
            {
                continue;
            }

            int separator = line.IndexOf('=');
            if (separator <= 0 || separator == line.Length - 1)
            {
                continue;
            }

            string displayName = line[..separator].Trim();
            string fileName = Path.GetFileName(line[(separator + 1)..].Trim());
            if (displayName.Length == 0 || fileName.Length == 0 || !fileName.EndsWith(".txt", StringComparison.OrdinalIgnoreCase))
            {
                continue;
            }

            string cultureName = CultureByFileName.TryGetValue(fileName, out string? mappedCulture)
                ? mappedCulture
                : "en";
            CultureInfo culture = CultureInfo.GetCultureInfo(cultureName);
            string id = culture.Name.Length == 0 ? cultureName : culture.Name;
            if (!identifiers.Add(id))
            {
                continue;
            }

            languages.Add(new LanguageDefinition(
                id,
                displayName,
                fileName,
                culture.Name,
                culture.TextInfo.IsRightToLeft));
        }

        if (!languages.Any(static language => string.Equals(language.Id, "en", StringComparison.OrdinalIgnoreCase)))
        {
            languages.Insert(0, new LanguageDefinition("en", "English", "English.txt", "en", false));
        }

        return languages
            .OrderBy(static language => string.Equals(language.Id, "en", StringComparison.OrdinalIgnoreCase) ? 0 : 1)
            .ThenBy(static language => language.DisplayName, StringComparer.CurrentCultureIgnoreCase)
            .ToArray();
    }
    public static string NormalizeIdentifier(string? value)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            return "en";
        }

        string normalized = value.Trim();
        string fileName = Path.GetFileName(normalized);
        if (!fileName.EndsWith(".txt", StringComparison.OrdinalIgnoreCase))
        {
            fileName += ".txt";
        }

        if (CultureByFileName.TryGetValue(fileName, out string? mappedCulture))
        {
            return mappedCulture;
        }

        try
        {
            return CultureInfo.GetCultureInfo(normalized).Name;
        }
        catch (CultureNotFoundException)
        {
            return "en";
        }
    }

}
