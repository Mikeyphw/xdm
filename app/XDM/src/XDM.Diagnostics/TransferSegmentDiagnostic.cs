namespace XDM.Diagnostics;

public sealed record TransferSegmentDiagnostic(
    int Index,
    long Start,
    long End,
    long Length,
    long Bytes,
    string State,
    DateTimeOffset UpdatedAt)
{
    public double Progress => Length <= 0 ? 0 : Math.Clamp(Bytes / (double)Length, 0, 1);

    public string RangeText => $"{Start}-{End}";

    public string ProgressText => $"{Bytes}/{Length} bytes ({Progress:P0})";
}
