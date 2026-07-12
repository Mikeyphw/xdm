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

        string? legacyKey = _legacyCatalog.FindLegacyKey(english);
        return legacyKey is null
            ? english
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
