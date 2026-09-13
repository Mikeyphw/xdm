namespace XDM.Core.Scheduling;

public sealed record SchedulerRuntimeState(
    DateTimeOffset LastEvaluationUtc,
    IReadOnlyDictionary<string, DateTimeOffset> LastStartedWindows,
    IReadOnlyDictionary<string, ScheduleRuntimeRunState>? ActiveRuns = null)
{
    public static SchedulerRuntimeState Empty { get; } = new(
        DateTimeOffset.MinValue,
        new Dictionary<string, DateTimeOffset>(StringComparer.Ordinal),
        new Dictionary<string, ScheduleRuntimeRunState>(StringComparer.Ordinal));

    public SchedulerRuntimeState Normalize(IReadOnlySet<string>? liveScheduleKeys = null)
    {
        Dictionary<string, DateTimeOffset> normalizedStarts = new(StringComparer.Ordinal);
        foreach ((string key, DateTimeOffset value) in LastStartedWindows ?? new Dictionary<string, DateTimeOffset>())
        {
            if (string.IsNullOrWhiteSpace(key))
            {
                continue;
            }

            if (liveScheduleKeys is not null && !liveScheduleKeys.Contains(key))
            {
                continue;
            }

            normalizedStarts[key.Trim()] = value;
        }

        Dictionary<string, ScheduleRuntimeRunState> normalizedRuns = new(StringComparer.Ordinal);
        foreach ((string key, ScheduleRuntimeRunState run) in ActiveRuns ?? new Dictionary<string, ScheduleRuntimeRunState>())
        {
            if (run is null || string.IsNullOrWhiteSpace(run.RunId) || string.IsNullOrWhiteSpace(run.ScheduleKey))
            {
                continue;
            }

            if (liveScheduleKeys is not null && !liveScheduleKeys.Contains(run.ScheduleKey))
            {
                continue;
            }

            normalizedRuns[key.Trim()] = run.Normalize();
        }

        return this with
        {
            LastStartedWindows = normalizedStarts,
            ActiveRuns = normalizedRuns
        };
    }
}

public sealed record ScheduleRuntimeRunState(
    string RunId,
    string ScheduleId,
    string ScheduleKey,
    string QueueId,
    DateTimeOffset WindowStartUtc,
    DateTimeOffset DefinitionCapturedUtc,
    IReadOnlyList<string> DownloadIds,
    bool CompletionStarted = false)
{
    public ScheduleRuntimeRunState Normalize()
        => this with
        {
            RunId = RunId.Trim(),
            ScheduleId = ScheduleId.Trim(),
            ScheduleKey = ScheduleKey.Trim(),
            QueueId = QueueId.Trim(),
            DownloadIds = DownloadIds?
                .Where(static id => !string.IsNullOrWhiteSpace(id))
                .Select(static id => id.Trim())
                .Distinct(StringComparer.Ordinal)
                .ToArray() ?? []
        };
}
