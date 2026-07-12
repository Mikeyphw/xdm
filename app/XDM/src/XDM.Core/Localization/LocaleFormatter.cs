using System.Globalization;

namespace XDM.Core.Localization;

public static class LocaleFormatter
{
    private static readonly string[] ByteUnits = ["B", "KB", "MB", "GB", "TB"];
    private static readonly string[] RateUnits = ["B/s", "KB/s", "MB/s", "GB/s", "TB/s"];

    public static string FormatBytes(long bytes, CultureInfo culture)
        => FormatScaled(Math.Max(0, bytes), ByteUnits, culture);

    public static string FormatRate(double bytesPerSecond, CultureInfo culture)
        => FormatScaled(Math.Max(0, bytesPerSecond), RateUnits, culture);

    public static string FormatDuration(TimeSpan duration, CultureInfo culture)
    {
        ArgumentNullException.ThrowIfNull(culture);
        TimeSpan normalized = duration < TimeSpan.Zero ? TimeSpan.Zero : duration;
        if (normalized.TotalHours >= 1)
        {
            return string.Format(culture, "{0:N0}h {1:N0}m", Math.Floor(normalized.TotalHours), normalized.Minutes);
        }

        if (normalized.TotalMinutes >= 1)
        {
            return string.Format(culture, "{0:N0}m {1:N0}s", normalized.Minutes, normalized.Seconds);
        }

        return string.Format(culture, "{0:N0}s", normalized.Seconds);
    }

    public static string FormatDateTime(DateTimeOffset value, CultureInfo culture)
    {
        ArgumentNullException.ThrowIfNull(culture);
        return value.ToLocalTime().ToString("g", culture);
    }

    private static string FormatScaled(double value, string[] units, CultureInfo culture)
    {
        ArgumentNullException.ThrowIfNull(culture);
        int unit = 0;
        while (value >= 1024 && unit < units.Length - 1)
        {
            value /= 1024;
            unit++;
        }

        return string.Format(culture, "{0:0.#} {1}", value, units[unit]);
    }
}
