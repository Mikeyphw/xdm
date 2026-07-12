namespace XDM.Media;

public sealed record ConversionResult(
    string SourcePath,
    string DestinationPath,
    ConversionPreset Preset,
    long OutputBytes,
    TimeSpan Elapsed);
