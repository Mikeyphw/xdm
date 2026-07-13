namespace XDM.App.ViewModels;

public sealed record MediaQualityOption(
    string Id,
    string Name,
    int? MaximumHeight = null,
    bool AudioOnly = false,
    bool PreferSmallest = false)
{
    public override string ToString() => Name;
}
