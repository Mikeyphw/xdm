namespace XDM.Media;

public sealed record ConversionQueueSnapshot(
    IReadOnlyList<ConversionJobSnapshot> Jobs,
    string? ActiveJobId)
{
    public static ConversionQueueSnapshot Empty { get; } = new([], null);
}
