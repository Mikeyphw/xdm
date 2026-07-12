namespace XDM.Core.Localization;

public sealed record AccessibilitySettings(
    bool HighContrastEnabled,
    int UiScalePercent,
    bool AnnounceStatusChanges)
{
    public static AccessibilitySettings Default { get; } = new(false, 100, true);

    public AccessibilitySettings Normalize()
        => this with { UiScalePercent = Math.Clamp(UiScalePercent, 75, 175) };
}
