using XDM.Core.Settings;

namespace XDM.Core.Policies;

public sealed record TransferPolicySnapshot(
    BandwidthProfile EffectiveProfile,
    TransferEnvironmentSnapshot Environment,
    bool IsPaused,
    string StatusMessage,
    IReadOnlyList<string> ActiveScheduleProfileIds,
    DateTimeOffset UpdatedAt)
{
    public static TransferPolicySnapshot Unrestricted { get; } = new(
        new BandwidthProfile("unrestricted", "Unrestricted", 32, 16, 0),
        TransferEnvironmentSnapshot.Unknown,
        false,
        "Smart transfer policy is inactive.",
        [],
        DateTimeOffset.UtcNow);
}
