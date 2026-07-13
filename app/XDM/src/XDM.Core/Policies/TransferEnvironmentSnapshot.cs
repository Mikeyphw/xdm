namespace XDM.Core.Policies;

public sealed record TransferEnvironmentSnapshot(
    bool IsNetworkAvailable,
    bool? IsMetered,
    bool? IsOnBattery,
    string Source,
    DateTimeOffset UpdatedAt)
{
    public static TransferEnvironmentSnapshot Unknown { get; } = new(
        true,
        null,
        null,
        "Unknown",
        DateTimeOffset.UtcNow);
}
