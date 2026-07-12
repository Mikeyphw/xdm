namespace XDM.Core.Localization;

public sealed record LocalizationSettings(
    string LanguageId,
    bool UseSystemLanguage)
{
    public static LocalizationSettings Default { get; } = new("en", true);

    public LocalizationSettings Normalize()
        => this with
        {
            LanguageId = string.IsNullOrWhiteSpace(LanguageId) ? "en" : LanguageId.Trim(),
        };
}
