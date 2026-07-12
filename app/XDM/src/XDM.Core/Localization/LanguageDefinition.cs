namespace XDM.Core.Localization;

public sealed record LanguageDefinition(
    string Id,
    string DisplayName,
    string FileName,
    string CultureName,
    bool IsRightToLeft);
