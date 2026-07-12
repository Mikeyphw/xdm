using Avalonia.Media;
using CommunityToolkit.Mvvm.ComponentModel;
using XDM.App.Services;

namespace XDM.App.ViewModels;

public sealed partial class NavigationItem : ObservableObject
{
    private readonly LocalizationService _localization;

    public NavigationItem(
        string id,
        string titleKey,
        string iconData,
        string summaryKey,
        LocalizationService localization)
    {
        Id = id;
        TitleKey = titleKey;
        IconData = Geometry.Parse(iconData);
        SummaryKey = summaryKey;
        _localization = localization;
        title = localization[titleKey];
        summary = localization[summaryKey];
    }

    public string Id { get; }

    public string TitleKey { get; }

    public Geometry IconData { get; }

    public string SummaryKey { get; }

    [ObservableProperty]
    private string title;

    [ObservableProperty]
    private string summary;

    public void Refresh()
    {
        Title = _localization[TitleKey];
        Summary = _localization[SummaryKey];
    }
}
