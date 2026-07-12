using System.Globalization;
using XDM.Core.Localization;

namespace XDM.Core.Tests;

public sealed class LocaleFormatterTests
{
    [Fact]
    public void FormatsScaledValuesUsingSelectedCulture()
    {
        CultureInfo portuguese = CultureInfo.GetCultureInfo("pt-BR");

        Assert.Equal("1,5 KB", LocaleFormatter.FormatBytes(1536, portuguese));
        Assert.Equal("2,5 MB/s", LocaleFormatter.FormatRate(2.5 * 1024 * 1024, portuguese));
    }

    [Fact]
    public void FormatsDurationWithoutInvariantNumberSeparators()
    {
        CultureInfo german = CultureInfo.GetCultureInfo("de-DE");

        Assert.Equal("1h 5m", LocaleFormatter.FormatDuration(TimeSpan.FromMinutes(65), german));
        Assert.Equal("42s", LocaleFormatter.FormatDuration(TimeSpan.FromSeconds(42), german));
    }

    [Fact]
    public void AccessibilitySettingsClampUiScale()
    {
        Assert.Equal(75, new AccessibilitySettings(false, 10, true).Normalize().UiScalePercent);
        Assert.Equal(175, new AccessibilitySettings(false, 500, true).Normalize().UiScalePercent);
    }
}
