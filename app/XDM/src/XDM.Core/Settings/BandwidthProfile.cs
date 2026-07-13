namespace XDM.Core.Settings;

public sealed record BandwidthProfile(
    string Id,
    string Name,
    int MaxConcurrentDownloads,
    int MaxConcurrentPerHost,
    long SpeedLimitBytesPerSecond)
{
    public BandwidthProfile Normalize()
        => this with
        {
            Id = string.IsNullOrWhiteSpace(Id) ? Guid.NewGuid().ToString("N") : Id.Trim(),
            Name = string.IsNullOrWhiteSpace(Name) ? "Transfer profile" : Name.Trim(),
            MaxConcurrentDownloads = Math.Clamp(MaxConcurrentDownloads, 1, 32),
            MaxConcurrentPerHost = Math.Clamp(MaxConcurrentPerHost, 1, 16),
            SpeedLimitBytesPerSecond = Math.Max(0, SpeedLimitBytesPerSecond)
        };
}
