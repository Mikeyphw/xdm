namespace XDM.Core.Settings;

public sealed record NetworkSettings(
    int ConnectTimeoutSeconds,
    int RequestTimeoutSeconds,
    int MaximumRetryAttempts,
    int RetryBaseDelayMilliseconds,
    int DefaultConnectionCount,
    int MaximumConnectionCount,
    long MinimumSegmentedSizeBytes,
    ProxySettings? Proxy)
{
    public static NetworkSettings Default { get; } = new(
        30,
        0,
        3,
        350,
        4,
        16,
        1024 * 1024,
        ProxySettings.SystemDefault);

    public NetworkSettings Normalize()
    {
        int maximumConnections = Math.Clamp(MaximumConnectionCount, 1, 32);
        return this with
        {
            ConnectTimeoutSeconds = Math.Clamp(ConnectTimeoutSeconds, 1, 300),
            RequestTimeoutSeconds = Math.Clamp(RequestTimeoutSeconds, 0, 86400),
            MaximumRetryAttempts = Math.Clamp(MaximumRetryAttempts, 1, 20),
            RetryBaseDelayMilliseconds = Math.Clamp(RetryBaseDelayMilliseconds, 100, 60000),
            DefaultConnectionCount = Math.Clamp(DefaultConnectionCount, 1, maximumConnections),
            MaximumConnectionCount = maximumConnections,
            MinimumSegmentedSizeBytes = Math.Clamp(MinimumSegmentedSizeBytes, 64 * 1024, 1024L * 1024 * 1024),
            Proxy = (Proxy ?? ProxySettings.SystemDefault).Normalize()
        };
    }
}
