namespace XDM.Diagnostics;

public sealed record DeterministicDownloadTestResult(
    DateTimeOffset StartedAt,
    DateTimeOffset CompletedAt,
    string EndpointOrigin,
    int StatusCode,
    long ExpectedBytes,
    long ReceivedBytes,
    string Sha256,
    TimeSpan Duration,
    double BytesPerSecond,
    bool Succeeded,
    string Summary)
{
    public string DurationText => $"{Duration.TotalSeconds:0.00} s";

    public string SpeedText => BytesPerSecond <= 0
        ? "n/a"
        : $"{BytesPerSecond / 1024d / 1024d:0.00} MiB/s";
}
