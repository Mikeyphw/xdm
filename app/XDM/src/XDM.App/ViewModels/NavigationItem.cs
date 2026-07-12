using CommunityToolkit.Mvvm.ComponentModel;
using XDM.App.Services;

namespace XDM.App.ViewModels;

public sealed partial class NavigationItem : ObservableObject
{
    private readonly LocalizationService _localization;

    public NavigationItem(
        string id,
        string titleKey,
        string glyph,
        string summaryKey,
        LocalizationService localization)
    {
        Id = id;
        TitleKey = titleKey;
        Glyph = glyph;
        SummaryKey = summaryKey;
        _localization = localization;
        title = localization[titleKey];
        summary = localization[summaryKey];
    }

    public string Id { get; }

    public string TitleKey { get; }

    public string Glyph { get; }

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
