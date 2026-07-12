using CommunityToolkit.Mvvm.ComponentModel;
using XDM.Media;

namespace XDM.App.ViewModels;

public partial class MediaFormatViewModel(MediaFormat format) : ObservableObject
{
    public MediaFormat Format { get; } = format;

    public string Id => Format.Id;

    public string DisplayName => Format.DisplayName;

    public string Kind => Format.StreamKind.ToString();

    public string Details
    {
        get
        {
            string dimensions = Format.Width is > 0 && Format.Height is > 0
                ? $"{Format.Width}×{Format.Height}"
                : string.Empty;
            string container = Format.Container ?? string.Empty;
            string encryption = Format.IsEncrypted ? "AES-128" : string.Empty;
            return string.Join(" • ", new[] { dimensions, container, encryption }
                .Where(static value => value.Length > 0));
        }
    }

    [ObservableProperty]
    private bool isSelected;
}
