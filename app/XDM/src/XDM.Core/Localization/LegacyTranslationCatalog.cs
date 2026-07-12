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

        LanguageDefinition? exact = Languages.FirstOrDefault(language =>
            string.Equals(language.Id, requested, StringComparison.OrdinalIgnoreCase)
            || string.Equals(language.CultureName, requested, StringComparison.OrdinalIgnoreCase));
        if (exact is not null)
        {
            return exact;
        }

        string neutral = requested.Split('-', StringSplitOptions.RemoveEmptyEntries)[0];
        LanguageDefinition? neutralMatch = Languages.FirstOrDefault(language =>
            string.Equals(language.CultureName.Split('-')[0], neutral, StringComparison.OrdinalIgnoreCase));
        return neutralMatch
            ?? Languages.FirstOrDefault(static language => string.Equals(language.Id, "en", StringComparison.OrdinalIgnoreCase))
            ?? new LanguageDefinition("en", "English", "English.txt", "en", false);
    }
}
