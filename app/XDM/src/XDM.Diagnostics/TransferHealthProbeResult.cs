namespace XDM.Diagnostics;

public sealed record TransferHealthProbeResult(
    string TargetOrigin,
    DateTimeOffset StartedAt,
    DateTimeOffset CompletedAt,
    IReadOnlyList<TransferHealthProbeStage> Stages,
    bool RangeSupported,
    long? DiskWriteBytesPerSecond)
{
    public bool Succeeded => Stages.All(static stage => stage.Status is not TransferHealthProbeStatus.Failed);

    public string Summary
    {
        get
        {
            int failed = Stages.Count(static stage => stage.Status == TransferHealthProbeStatus.Failed);
            int warnings = Stages.Count(static stage => stage.Status == TransferHealthProbeStatus.Warning);
            return failed > 0
                ? $"{failed} health stage{(failed == 1 ? string.Empty : "s")} failed."
                : warnings > 0
                    ? $"Probe completed with {warnings} warning{(warnings == 1 ? string.Empty : "s")}."
                    : "All bounded health stages passed.";
        }
    }
}
