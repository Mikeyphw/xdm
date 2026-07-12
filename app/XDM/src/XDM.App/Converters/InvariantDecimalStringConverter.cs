using System.Globalization;
using Avalonia.Data.Converters;

namespace XDM.App.Converters;

public sealed class InvariantDecimalStringConverter : IValueConverter
{
    public object? Convert(object? value, Type targetType, object? parameter, CultureInfo culture)
    {
        if (value is null)
        {
            return null;
        }

        if (value is decimal decimalValue)
        {
            return decimalValue;
        }

        string text = System.Convert.ToString(value, CultureInfo.InvariantCulture) ?? string.Empty;
        return decimal.TryParse(text, NumberStyles.Number, CultureInfo.InvariantCulture, out decimal parsed)
            ? parsed
            : null;
    }

    public object? ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture)
        => value is decimal decimalValue
            ? decimalValue.ToString(CultureInfo.InvariantCulture)
            : string.Empty;
}
