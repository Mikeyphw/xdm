namespace XDM.Core.Settings;

public sealed record HistoryRetentionSettings(
    bool Enabled,
    int RetentionDays,
    int MaximumEntries)
{
    public static HistoryRetentionSettings Default { get; } = new(false, 90, 10_000);

    public HistoryRetentionSettings Normalize()
        => this with
        {
            RetentionDays = Math.Clamp(RetentionDays, 1, 3650),
            MaximumEntries = Math.Clamp(MaximumEntries, 100, 100_000)
        };
}
