using CommunityToolkit.Mvvm.ComponentModel;
using XDM.Core.Settings;

namespace XDM.App.ViewModels;

public partial class BandwidthProfileEditorViewModel : ObservableObject
{
    public BandwidthProfileEditorViewModel(BandwidthProfile profile)
    {
        ArgumentNullException.ThrowIfNull(profile);
        Id = profile.Id;
        name = profile.Name;
        maxConcurrentDownloads = profile.MaxConcurrentDownloads.ToString(System.Globalization.CultureInfo.InvariantCulture);
        maxConcurrentPerHost = profile.MaxConcurrentPerHost.ToString(System.Globalization.CultureInfo.InvariantCulture);
        speedLimitKbps = (profile.SpeedLimitBytesPerSecond / 1024L)
            .ToString(System.Globalization.CultureInfo.InvariantCulture);
    }

    public string Id { get; }

    [ObservableProperty]
    private string name;

    [ObservableProperty]
    private string maxConcurrentDownloads;

    [ObservableProperty]
    private string maxConcurrentPerHost;

    [ObservableProperty]
    private string speedLimitKbps;

    public BandwidthProfile ToDefinition()
    {
        int concurrency = int.TryParse(
            MaxConcurrentDownloads,
            System.Globalization.NumberStyles.Integer,
            System.Globalization.CultureInfo.InvariantCulture,
            out int parsedConcurrency)
                ? parsedConcurrency
                : 1;
        int perHost = int.TryParse(
            MaxConcurrentPerHost,
            System.Globalization.NumberStyles.Integer,
            System.Globalization.CultureInfo.InvariantCulture,
            out int parsedPerHost)
                ? parsedPerHost
                : 1;
        long speedKbps = long.TryParse(
            SpeedLimitKbps,
            System.Globalization.NumberStyles.Integer,
            System.Globalization.CultureInfo.InvariantCulture,
            out long parsedSpeed)
                ? parsedSpeed
                : 0;
        return new BandwidthProfile(
            Id,
            string.IsNullOrWhiteSpace(Name) ? "Transfer profile" : Name.Trim(),
            concurrency,
            perHost,
            Math.Max(0, speedKbps) * 1024).Normalize();
    }
}
