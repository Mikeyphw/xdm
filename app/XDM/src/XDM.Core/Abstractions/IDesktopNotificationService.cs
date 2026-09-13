namespace XDM.Core.Abstractions;

public interface IDesktopNotificationService
{
    Task<DesktopNotificationDeliveryResult> ShowAsync(
        string title,
        string message,
        CancellationToken cancellationToken = default);
}

public sealed record DesktopNotificationDeliveryResult(
    bool Delivered,
    bool Supported,
    bool TimedOut,
    string Channel,
    string? Failure = null)
{
    public static DesktopNotificationDeliveryResult DeliveredBy(string channel)
        => new(true, true, false, channel);

    public static DesktopNotificationDeliveryResult Unsupported(string reason)
        => new(false, false, false, "unsupported", reason);

    public static DesktopNotificationDeliveryResult Failed(string channel, string reason)
        => new(false, true, false, channel, reason);

    public static DesktopNotificationDeliveryResult TimedOutResult(string channel, TimeSpan timeout)
        => new(false, true, true, channel, $"Notification command timed out after {timeout.TotalSeconds:0.#} seconds.");

    public static DesktopNotificationDeliveryResult Suppressed(string reason)
        => new(false, true, false, "suppressed", reason);
}
