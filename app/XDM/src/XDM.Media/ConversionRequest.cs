namespace XDM.Media;

public sealed record ConversionRequest(
    string SourcePath,
    string DestinationPath,
    string PresetId,
    bool OverwriteExisting = false,
    bool PreserveSourceTimestamp = true);
