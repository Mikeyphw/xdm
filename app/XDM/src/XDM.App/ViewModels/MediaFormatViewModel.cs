using CommunityToolkit.Mvvm.ComponentModel;
using XDM.Media;

namespace XDM.App.ViewModels;

public partial class MediaFormatViewModel(MediaFormat format) : ObservableObject
{
    public MediaFormat Format { get; } = format;

    public string Id => Format.Id;

    public string DisplayName => Format.DisplayName;

    public string Kind => Format.StreamKind.ToString();

    public string Quality
        => Format.Height is > 0
            ? $"{Format.Height}p{(Format.FrameRate is > 0 ? $" {Format.FrameRate.Value.ToString("0.#", System.Globalization.CultureInfo.CurrentCulture)} fps" : string.Empty)}"
            : Format.StreamKind == MediaStreamKind.Audio ? "Audio" : "—";

    public string Bitrate
        => Format.Bandwidth is > 0
            ? $"{(Format.Bandwidth.Value / 1000d).ToString("0.#", System.Globalization.CultureInfo.CurrentCulture)} kbps"
            : "—";

    public string Language
        => string.IsNullOrWhiteSpace(Format.Language) ? "Undetermined" : Format.Language;

    public string Container
        => string.IsNullOrWhiteSpace(Format.Container) ? "—" : Format.Container.ToUpperInvariant();

    public string Codec
        => string.IsNullOrWhiteSpace(Format.Codecs) ? "—" : Format.Codecs;

    public string Details
    {
        get
        {
            string dimensions = Format.Width is > 0 && Format.Height is > 0
                ? $"{Format.Width}×{Format.Height}"
                : string.Empty;
            string container = Format.Container ?? string.Empty;
            string encryption = Format.IsEncrypted ? "Encrypted" : string.Empty;
            return string.Join(" • ", new[] { dimensions, container, encryption }
                .Where(static value => value.Length > 0));
        }
    }

    [ObservableProperty]
    private bool isSelected;

    [ObservableProperty]
    private bool isChosen;
}
