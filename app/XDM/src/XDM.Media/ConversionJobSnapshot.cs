namespace XDM.Media;

public sealed record ConversionJobSnapshot(
    string Id,
    ConversionRequest Request,
    string PresetName,
    ConversionJobState State,
    double? ProgressFraction,
    string StatusMessage,
    string? ErrorMessage,
    DateTimeOffset CreatedAt,
    DateTimeOffset? StartedAt,
    DateTimeOffset? CompletedAt,
    long? OutputBytes);
