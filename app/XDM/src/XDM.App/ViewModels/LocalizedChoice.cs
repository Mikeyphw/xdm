using System.ComponentModel;
using XDM.App.Services;

namespace XDM.App.ViewModels;

public sealed class LocalizedChoice<T> : INotifyPropertyChanged where T : struct, Enum
{
    private readonly LocalizationService _localization;
    private string _label;

    public LocalizedChoice(T value, string labelKey, LocalizationService localization)
    {
        ArgumentNullException.ThrowIfNull(localization);
        Value = value;
        LabelKey = labelKey;
        _localization = localization;
        _label = localization[labelKey];
    }

    public event PropertyChangedEventHandler? PropertyChanged;

    public T Value { get; }

    public string LabelKey { get; }

    public string Label
    {
        get => _label;
        private set
        {
            if (string.Equals(_label, value, StringComparison.Ordinal))
            {
                return;
            }

            _label = value;
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(Label)));
        }
    }

    public void Refresh()
        => Label = _localization[LabelKey];

    public override string ToString()
        => Label;
}
