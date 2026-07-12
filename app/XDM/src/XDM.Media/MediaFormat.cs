namespace XDM.Media;

public sealed record MediaFormat(
    string Id,
    MediaStreamKind StreamKind,
    Uri ManifestUri,
    string? Container,
    string? Codecs,
    long? Bandwidth,
    int? Width,
    int? Height,
    double? FrameRate,
    string? Language,
    string? Name,
    bool IsDefault,
    bool IsEncrypted,
    string? ProviderData = null)
{
    public string DisplayName
    {
        get
        {
            string resolution = Height is > 0 ? $"{Height}p" : string.Empty;
            string bandwidth = Bandwidth is > 0 ? $"{Bandwidth.Value / 1000} kbps" : string.Empty;
            string language = string.IsNullOrWhiteSpace(Language) ? string.Empty : Language;
            string[] parts = [Name ?? string.Empty, resolution, bandwidth, language, Codecs ?? string.Empty];
            string value = string.Join(" • ", parts.Where(static part => part.Length > 0));
            return value.Length == 0 ? Id : value;
        }
    }
}
