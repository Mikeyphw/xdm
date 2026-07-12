namespace XDM.Media;

public interface IConversionService
{
    IReadOnlyList<ConversionPreset> Presets { get; }

    Task<ExternalToolHealth> GetHealthAsync(CancellationToken cancellationToken = default);

    Task<MediaInspection> InspectAsync(
        string sourcePath,
        CancellationToken cancellationToken = default);

    Task<ConversionResult> ConvertAsync(
        ConversionRequest request,
        IProgress<ConversionProgress>? progress = null,
        CancellationToken cancellationToken = default);
}
