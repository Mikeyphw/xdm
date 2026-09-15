using System.ComponentModel;
using System.Globalization;
using System.Reflection;
using System.Text.Json;
using XDM.Core.Localization;
using XDM.Core.Settings;

namespace XDM.App.Services;

public sealed class LocalizationService : INotifyPropertyChanged, IDisposable
{
    private const string ModernResourceName = "XDM.App.Localization.strings.en.json";
    private readonly ISettingsService _settingsService;
    private readonly LegacyTranslationCatalog _legacyCatalog;
    private readonly Dictionary<string, string> _modernEnglish;
    private readonly Dictionary<string, Dictionary<string, string>> _modernResources;
    private readonly CultureInfo _systemCulture;
    private LanguageDefinition _currentLanguage;
    private CultureInfo _culture;
    private AccessibilitySettings _accessibility = AccessibilitySettings.Default;
    private bool _disposed;

    public LocalizationService(ISettingsService settingsService)
    {
        _settingsService = settingsService;
        _systemCulture = CultureInfo.CurrentUICulture;
        _modernEnglish = LoadModernEnglish();
        _modernResources = LoadModernResourceCatalog(_modernEnglish);
        _legacyCatalog = LegacyTranslationCatalog.Load(Path.Combine(AppContext.BaseDirectory, "Lang"));
        _currentLanguage = _legacyCatalog.ResolveLanguage("en", _systemCulture, useSystemLanguage: true);
        _culture = CultureInfo.GetCultureInfo(_currentLanguage.CultureName);
        Apply(settingsService.Current);
        settingsService.Changed += OnSettingsChanged;
    }

    public event PropertyChangedEventHandler? PropertyChanged;

    public event EventHandler? Changed;

    public IReadOnlyList<LanguageDefinition> Languages => _legacyCatalog.Languages;

    public LanguageDefinition CurrentLanguage => _currentLanguage;

    public CultureInfo Culture => _culture;

    public bool IsRightToLeft => _currentLanguage.IsRightToLeft;

    public bool HighContrastEnabled => _accessibility.HighContrastEnabled;

    public int UiScalePercent => _accessibility.UiScalePercent;

    public double UiScaleFactor => _accessibility.UiScalePercent / 100d;

    public bool AnnounceStatusChanges => _accessibility.AnnounceStatusChanges;

    public string this[string key] => Get(key, key);

    public string Get(string key, string fallback)
    {
        if (!_modernEnglish.TryGetValue(key, out string? english))
        {
            english = fallback;
        }

        if (TryGetModernString(key, out string? modernLocalized))
        {
            return modernLocalized;
        }

        string? legacyKey = _legacyCatalog.FindLegacyKey(english);
        return legacyKey is null
            ? english!
            : _legacyCatalog.GetString(_currentLanguage.Id, legacyKey, english);
    }

    public string GetStatus(XDM.Core.Downloads.DownloadState state)
        => Get($"status_{state.ToString().ToLowerInvariant()}", state.ToString());

    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }

        _disposed = true;
        _settingsService.Changed -= OnSettingsChanged;
        GC.SuppressFinalize(this);
    }

    private void OnSettingsChanged(object? sender, ApplicationSettings settings)
        => Apply(settings);

    private void Apply(ApplicationSettings settings)
    {
        LocalizationSettings localization = (settings.Localization ?? LocalizationSettings.Default).Normalize();
        AccessibilitySettings accessibility = (settings.Accessibility ?? AccessibilitySettings.Default).Normalize();
        LanguageDefinition resolved = _legacyCatalog.ResolveLanguage(
            localization.LanguageId,
            _systemCulture,
            localization.UseSystemLanguage);
        CultureInfo culture = CultureInfo.GetCultureInfo(resolved.CultureName);
        bool changed = !string.Equals(_currentLanguage.Id, resolved.Id, StringComparison.OrdinalIgnoreCase)
            || _accessibility != accessibility;

        _currentLanguage = resolved;
        _culture = culture;
        _accessibility = accessibility;
        CultureInfo.DefaultThreadCurrentCulture = culture;
        CultureInfo.DefaultThreadCurrentUICulture = culture;
        CultureInfo.CurrentCulture = culture;
        CultureInfo.CurrentUICulture = culture;

        if (!changed)
        {
            return;
        }

        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(CurrentLanguage)));
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(Culture)));
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(IsRightToLeft)));
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(HighContrastEnabled)));
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(UiScalePercent)));
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(UiScaleFactor)));
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(AnnounceStatusChanges)));
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs("Item[]"));
        Changed?.Invoke(this, EventArgs.Empty);
    }


    private bool TryGetModernString(string key, out string? value)
    {
        foreach (string candidate in GetModernResourceCandidates())
        {
            if (_modernResources.TryGetValue(candidate, out Dictionary<string, string>? resources)
                && resources.TryGetValue(key, out string? candidateValue)
                && candidateValue.Length > 0)
            {
                value = candidateValue;
                return true;
            }
        }

        value = null;
        return false;
    }

    private IEnumerable<string> GetModernResourceCandidates()
    {
        if (string.Equals(_currentLanguage.Id, "en", StringComparison.OrdinalIgnoreCase)
            || string.Equals(_currentLanguage.CultureName, "en", StringComparison.OrdinalIgnoreCase))
        {
            yield return "en";
            yield break;
        }

        foreach (string candidate in new[] { _currentLanguage.Id, _currentLanguage.CultureName })
        {
            if (!string.IsNullOrWhiteSpace(candidate) && !string.Equals(candidate, "en", StringComparison.OrdinalIgnoreCase))
            {
                yield return candidate;
            }
        }

        CultureInfo culture;
        try
        {
            culture = CultureInfo.GetCultureInfo(_currentLanguage.CultureName);
        }
        catch (CultureNotFoundException)
        {
            yield break;
        }

        CultureInfo current = culture.Parent;
        while (!string.IsNullOrEmpty(current.Name) && !string.Equals(current.Name, "en", StringComparison.OrdinalIgnoreCase))
        {
            yield return current.Name;
            current = current.Parent;
        }
    }

    private static Dictionary<string, Dictionary<string, string>> LoadModernResourceCatalog(
        Dictionary<string, string> english)
    {
        Dictionary<string, Dictionary<string, string>> resources = new(StringComparer.OrdinalIgnoreCase)
        {
            ["en"] = english
        };

        string directory = Path.Combine(AppContext.BaseDirectory, "Localization");
        if (!Directory.Exists(directory))
        {
            return resources;
        }

        foreach (string file in Directory.EnumerateFiles(directory, "strings.*.json", SearchOption.TopDirectoryOnly))
        {
            string name = Path.GetFileNameWithoutExtension(file);
            if (!name.StartsWith("strings.", StringComparison.OrdinalIgnoreCase))
            {
                continue;
            }

            string cultureName = name["strings.".Length..];
            if (cultureName.Length == 0)
            {
                continue;
            }

            using FileStream stream = File.OpenRead(file);
            Dictionary<string, string>? loaded = JsonSerializer.Deserialize<Dictionary<string, string>>(stream);
            if (loaded is not null && loaded.Count > 0)
            {
                resources[cultureName] = loaded;
            }
        }

        return resources;
    }

    private static Dictionary<string, string> LoadModernEnglish()
    {
        string filePath = Path.Combine(AppContext.BaseDirectory, "Localization", "strings.en.json");
        if (File.Exists(filePath))
        {
            using FileStream fileStream = File.OpenRead(filePath);
            return JsonSerializer.Deserialize<Dictionary<string, string>>(fileStream)
                ?? throw new InvalidDataException("The on-disk English localization resource is invalid.");
        }

        Assembly assembly = typeof(LocalizationService).Assembly;
        string resourceName = assembly.GetManifestResourceNames()
            .FirstOrDefault(name => name.Equals(ModernResourceName, StringComparison.Ordinal)
                || name.EndsWith(".Localization.strings.en.json", StringComparison.Ordinal)
                || name.EndsWith("Localization.strings.en.json", StringComparison.Ordinal)
                || name.EndsWith("strings.en.json", StringComparison.Ordinal))
            ?? throw new InvalidOperationException(
                $"Localization resource '{ModernResourceName}' was not found on disk at '{filePath}' " +
                $"or embedded in the assembly. Available embedded resources: {string.Join(", ", assembly.GetManifestResourceNames())}");

        using Stream stream = assembly.GetManifestResourceStream(resourceName)
            ?? throw new InvalidOperationException($"Localization resource '{resourceName}' could not be opened.");
        return JsonSerializer.Deserialize<Dictionary<string, string>>(stream)
            ?? throw new InvalidDataException("The embedded English localization resource is invalid.");
    }
}
