namespace XDM.Media;

public sealed record ConversionPreset(
    string Id,
    string Name,
    string Description,
    ConversionKind Kind,
    string FileExtension);
