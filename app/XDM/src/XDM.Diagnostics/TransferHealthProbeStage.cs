namespace XDM.Diagnostics;

public sealed record TransferHealthProbeStage(
    string Name,
    TransferHealthProbeStatus Status,
    TimeSpan Duration,
    string Summary,
    IReadOnlyDictionary<string, string?> Details)
{
    public string DurationText => Duration <= TimeSpan.Zero ? "—" : $"{Duration.TotalMilliseconds:0} ms";

    public string DetailsText => Details.Count == 0
        ? string.Empty
        : string.Join(" · ", Details.Select(static pair => $"{pair.Key}: {pair.Value}"));
}
