using System.Globalization;

namespace XDM.Core.Localization;

public sealed class LegacyTranslationCatalog
{
    private const long MaximumLanguageFileBytes = 1024 * 1024;
    private const int MaximumEntries = 4096;
    private const int MaximumKeyLength = 160;
    private const int MaximumValueLength = 8192;
    private static readonly string[] DefaultIndexLines = ["English=English.txt"];

    private readonly Dictionary<string, Dictionary<string, string>> _translations;
    private readonly Dictionary<string, string> _english;
    private readonly Dictionary<string, string> _englishReverse;

    private LegacyTranslationCatalog(
        LanguageDefinition[] languages,
        Dictionary<string, Dictionary<string, string>> translations)
    {
        Languages = languages;
        _translations = translations;
        _english = translations.TryGetValue("en", out Dictionary<string, string>? english)
            ? english
            : new Dictionary<string, string>(StringComparer.Ordinal);
        _englishReverse = _english
            .Where(static pair => pair.Value.Length > 0)
            .GroupBy(static pair => pair.Value, StringComparer.Ordinal)
            .ToDictionary(static group => group.Key, static group => group.First().Key, StringComparer.Ordinal);
    }

    public IReadOnlyList<LanguageDefinition> Languages { get; }

    public static LegacyTranslationCatalog Load(string directory)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(directory);
        string fullDirectory = Path.GetFullPath(directory);
        string indexPath = Path.Combine(fullDirectory, "index.txt");
        LanguageDefinition[] languages = (File.Exists(indexPath)
            ? LegacyLanguageIndex.Parse(File.ReadLines(indexPath))
            : LegacyLanguageIndex.Parse(DefaultIndexLines))
            .ToArray();
        Dictionary<string, Dictionary<string, string>> translations = new(StringComparer.OrdinalIgnoreCase);

        foreach (LanguageDefinition language in languages)
        {
            string path = Path.Combine(fullDirectory, Path.GetFileName(language.FileName));
            if (!File.Exists(path) || new FileInfo(path).Length > MaximumLanguageFileBytes)
            {
                translations[language.Id] = new Dictionary<string, string>(StringComparer.Ordinal);
                continue;
            }

            translations[language.Id] = Parse(File.ReadLines(path));
        }

        if (!translations.ContainsKey("en"))
        {
            translations["en"] = new Dictionary<string, string>(StringComparer.Ordinal);
        }

        return new LegacyTranslationCatalog(languages, translations);
    }

    public static Dictionary<string, string> Parse(IEnumerable<string> lines)
    {
        ArgumentNullException.ThrowIfNull(lines);
        Dictionary<string, string> values = new(StringComparer.Ordinal);
        foreach (string rawLine in lines.Take(MaximumEntries))
        {
            string line = rawLine.TrimEnd().TrimStart('\uFEFF');
            if (line.Length == 0 || line.StartsWith('#') || line.StartsWith(';'))
            {
                continue;
            }

            int separator = line.IndexOf('=');
            if (separator <= 0)
            {
                continue;
            }

            string key = line[..separator].Trim();
            string value = line[(separator + 1)..].Trim();
            if (key.Length == 0 || key.Length > MaximumKeyLength || value.Length > MaximumValueLength)
            {
                continue;
            }

            values[key] = value;
        }

        return values;
    }

    public string GetString(string languageId, string key, string fallback)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(key);
        if (_translations.TryGetValue(languageId, out Dictionary<string, string>? selected)
            && selected.TryGetValue(key, out string? translated)
            && translated.Length > 0)
        {
            return translated;
        }

        return _english.TryGetValue(key, out string? english) && english.Length > 0
            ? english
            : fallback;
    }

    public string? FindLegacyKey(string englishText)
        => _englishReverse.TryGetValue(englishText, out string? key) ? key : null;

    public LanguageDefinition ResolveLanguage(string requestedLanguageId, CultureInfo systemCulture, bool useSystemLanguage)
    {
        ArgumentNullException.ThrowIfNull(systemCulture);
        string requested = useSystemLanguage ? systemCulture.Name : requestedLanguageId;
        if (string.IsNullOrWhiteSpace(requested))
        {
            requested = "en";
        }

        string normalized = LegacyLanguageIndex.NormalizeIdentifier(requested);
        LanguageDefinition? exact = Languages.FirstOrDefault(language =>
            string.Equals(language.Id, requested, StringComparison.OrdinalIgnoreCase)
            || string.Equals(language.CultureName, requested, StringComparison.OrdinalIgnoreCase)
            || string.Equals(language.Id, normalized, StringComparison.OrdinalIgnoreCase)
            || string.Equals(language.CultureName, normalized, StringComparison.OrdinalIgnoreCase));
        if (exact is not null)
        {
            return exact;
        }

        CultureInfo requestedCulture = GetCultureOrDefault(normalized, systemCulture);
        string language = requestedCulture.TwoLetterISOLanguageName.Length > 0
            ? requestedCulture.TwoLetterISOLanguageName
            : normalized.Split('-', StringSplitOptions.RemoveEmptyEntries)[0];
        string? script = ResolveScriptHint(requestedCulture.Name, requested);

        LanguageDefinition? scriptMatch = script is null
            ? null
            : Languages.FirstOrDefault(languageDefinition =>
                string.Equals(GetLanguage(languageDefinition.CultureName), language, StringComparison.OrdinalIgnoreCase)
                && string.Equals(ResolveScriptHint(languageDefinition.CultureName, languageDefinition.Id), script, StringComparison.OrdinalIgnoreCase));
        if (scriptMatch is not null)
        {
            return scriptMatch;
        }

        LanguageDefinition? parentMatch = FindParentCultureMatch(requestedCulture);
        if (parentMatch is not null)
        {
            return parentMatch;
        }

        LanguageDefinition? neutralMatch = Languages.FirstOrDefault(languageDefinition =>
            string.Equals(GetLanguage(languageDefinition.CultureName), language, StringComparison.OrdinalIgnoreCase));
        return neutralMatch
            ?? Languages.FirstOrDefault(static languageDefinition => string.Equals(languageDefinition.Id, "en", StringComparison.OrdinalIgnoreCase))
            ?? new LanguageDefinition("en", "English", "English.txt", "en", false);
    }

    private LanguageDefinition? FindParentCultureMatch(CultureInfo culture)
    {
        CultureInfo current = culture;
        while (!string.IsNullOrEmpty(current.Name))
        {
            LanguageDefinition? match = Languages.FirstOrDefault(language =>
                string.Equals(language.CultureName, current.Name, StringComparison.OrdinalIgnoreCase)
                || string.Equals(language.Id, current.Name, StringComparison.OrdinalIgnoreCase));
            if (match is not null)
            {
                return match;
            }

            current = current.Parent;
        }

        return null;
    }

    private static CultureInfo GetCultureOrDefault(string cultureName, CultureInfo fallback)
    {
        try
        {
            return CultureInfo.GetCultureInfo(cultureName);
        }
        catch (CultureNotFoundException)
        {
            return fallback.Name.Length > 0 ? fallback : CultureInfo.GetCultureInfo("en");
        }
    }

    private static string GetLanguage(string cultureName)
        => cultureName.Split('-', StringSplitOptions.RemoveEmptyEntries).FirstOrDefault() ?? cultureName;

    private static string? ResolveScriptHint(string cultureName, string original)
    {
        string token = cultureName.Length > 0 ? cultureName : original;
        string[] parts = token.Split('-', StringSplitOptions.RemoveEmptyEntries);
        string? explicitScript = parts.FirstOrDefault(static part =>
            part.Length == 4
            && (string.Equals(part, "Hans", StringComparison.OrdinalIgnoreCase)
                || string.Equals(part, "Hant", StringComparison.OrdinalIgnoreCase)
                || string.Equals(part, "Latn", StringComparison.OrdinalIgnoreCase)
                || string.Equals(part, "Cyrl", StringComparison.OrdinalIgnoreCase)));
        if (explicitScript is not null)
        {
            return CultureInfo.InvariantCulture.TextInfo.ToTitleCase(explicitScript.ToLowerInvariant());
        }

        if (parts.Length >= 2 && string.Equals(parts[0], "zh", StringComparison.OrdinalIgnoreCase))
        {
            string region = parts[^1].ToUpperInvariant();
            return region is "TW" or "HK" or "MO" ? "Hant" : region is "CN" or "SG" ? "Hans" : null;
        }

        if (parts.Length >= 2 && string.Equals(parts[0], "sr", StringComparison.OrdinalIgnoreCase))
        {
            return string.Equals(parts[^1], "BA", StringComparison.OrdinalIgnoreCase) ? "Latn" : "Cyrl";
        }

        return null;
    }
}
