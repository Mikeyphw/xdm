namespace XDM.Media;

public sealed record ConversionProgress(
    ConversionJobState State,
    string Message,
    double? Fraction = null,
    TimeSpan? ProcessedDuration = null,
    long? OutputBytes = null,
    string? Speed = null);
