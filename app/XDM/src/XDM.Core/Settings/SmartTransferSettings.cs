namespace XDM.Core.Settings;

public enum TransferPolicyBehavior
{
    Ignore,
    UseProfile,
    Pause
}

public enum NetworkCostOverride
{
    Auto,
    Unmetered,
    Metered
}

public enum PowerSourceOverride
{
    Auto,
    AcPower,
    Battery
}

public sealed record SmartTransferSettings(
    bool Enabled,
    string ActiveProfileId,
    TransferPolicyBehavior MeteredBehavior,
    string MeteredProfileId,
    TransferPolicyBehavior BatteryBehavior,
    string BatteryProfileId,
    NetworkCostOverride NetworkCostOverride,
    PowerSourceOverride PowerSourceOverride,
    bool PauseWhenOffline,
    IReadOnlyList<BandwidthProfile> Profiles)
{
    public static SmartTransferSettings Default { get; } = new(
        true,
        "balanced",
        TransferPolicyBehavior.UseProfile,
        "metered",
        TransferPolicyBehavior.UseProfile,
        "battery",
        NetworkCostOverride.Auto,
        PowerSourceOverride.Auto,
        true,
        [
            new BandwidthProfile("balanced", "Balanced", 4, 2, 0),
            new BandwidthProfile("focus", "Focus", 2, 1, 2L * 1024 * 1024),
            new BandwidthProfile("gaming", "Gaming", 1, 1, 512L * 1024),
            new BandwidthProfile("metered", "Metered", 1, 1, 256L * 1024),
            new BandwidthProfile("battery", "Battery saver", 2, 1, 1L * 1024 * 1024),
            new BandwidthProfile("overnight", "Overnight", 8, 4, 0)
        ]);

    public SmartTransferSettings Normalize()
    {
        BandwidthProfile[] profiles = Profiles?
            .Select(static profile => profile.Normalize())
            .Where(static profile => profile.Id.Length > 0)
            .DistinctBy(static profile => profile.Id, StringComparer.Ordinal)
            .Take(32)
            .ToArray() ?? [];
        if (profiles.Length == 0)
        {
            profiles = Default.Profiles.ToArray();
        }

        string active = ResolveProfileId(ActiveProfileId, profiles, profiles[0].Id);
        string metered = ResolveProfileId(MeteredProfileId, profiles, active);
        string battery = ResolveProfileId(BatteryProfileId, profiles, active);
        return this with
        {
            ActiveProfileId = active,
            MeteredProfileId = metered,
            BatteryProfileId = battery,
            Profiles = profiles
        };
    }

    private static string ResolveProfileId(
        string? candidate,
        BandwidthProfile[] profiles,
        string fallback)
        => profiles.Any(profile => string.Equals(profile.Id, candidate, StringComparison.Ordinal))
            ? candidate!
            : fallback;
}
