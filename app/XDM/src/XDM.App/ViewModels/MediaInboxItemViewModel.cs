using System.Security.Cryptography;
using System.Text;
using XDM.Media;

namespace XDM.App.ViewModels;

public sealed class MediaInboxItemViewModel
{
    public MediaInboxItemViewModel(
        MediaCatalog catalog,
        MediaRequestMetadata metadata,
        string? sourcePage,
        string? browser,
        DateTimeOffset detectedAt)
    {
        ArgumentNullException.ThrowIfNull(catalog);
        ArgumentNullException.ThrowIfNull(metadata);
        Catalog = catalog;
        Metadata = metadata;
        SourcePage = sourcePage;
        Browser = browser;
        DetectedAt = detectedAt;
        string identity = $"{sourcePage}|{catalog.Source.AbsoluteUri}";
        Id = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(identity)))[..16];
    }

    public string Id { get; }

    public MediaCatalog Catalog { get; }

    public MediaRequestMetadata Metadata { get; }

    public string? SourcePage { get; }

    public string? Browser { get; }

    public DateTimeOffset DetectedAt { get; }

    public string Title => Catalog.Title;

    public string GroupName
    {
        get
        {
            if (Uri.TryCreate(SourcePage, UriKind.Absolute, out Uri? page))
            {
                return page.Host;
            }

            return Catalog.Source.Host;
        }
    }

    public string SourceLabel
        => string.IsNullOrWhiteSpace(Browser)
            ? GroupName
            : $"{Browser} • {GroupName}";

    public string ProtocolLabel => Catalog.Kind switch
    {
        MediaKind.Hls => "HLS",
        MediaKind.Dash => "DASH",
        MediaKind.DirectFile => "Direct",
        MediaKind.ExternalProvider => Catalog.Provider,
        _ => Catalog.Kind.ToString()
    };

    public string StatusLabel => Catalog.IsLive ? "Live" : "On demand";

    public string VariantSummary => $"{Catalog.Formats.Count} variant(s)";

    public string DetectedAtText => DetectedAt.ToLocalTime().ToString("g", System.Globalization.CultureInfo.CurrentCulture);

    public string AccessibleSummary
        => $"{Title}, {ProtocolLabel}, {StatusLabel}, {VariantSummary}, from {SourceLabel}";
}
