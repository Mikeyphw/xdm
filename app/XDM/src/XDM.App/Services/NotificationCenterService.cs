using XDM.Core.Abstractions;
using XDM.Platform;

namespace XDM.App.Services;

public enum NotificationCenterSeverity
{
    Information,
    Success,
    Warning,
    Error
}

public sealed record NotificationCenterEntry(
    string Id,
    DateTimeOffset Timestamp,
    string Title,
    string Message,
    NotificationCenterSeverity Severity,
    string? DownloadId = null,
    bool DesktopNotificationShown = false);

public interface INotificationCenterService : IDesktopNotificationService
{
    event EventHandler? Changed;

    IReadOnlyList<NotificationCenterEntry> Snapshot();

    Task PublishAsync(
        string title,
        string message,
        NotificationCenterSeverity severity = NotificationCenterSeverity.Information,
        string? downloadId = null,
        bool showDesktopNotification = true,
        CancellationToken cancellationToken = default);

    void Dismiss(string id);

    void Clear();
}

public sealed class NotificationCenterService : INotificationCenterService
{
    private const int Capacity = 200;
    private readonly DesktopNotificationService _desktopNotifications;
    private readonly object _sync = new();
    private readonly List<NotificationCenterEntry> _entries = [];

    public NotificationCenterService(DesktopNotificationService desktopNotifications)
    {
        _desktopNotifications = desktopNotifications;
    }

    public event EventHandler? Changed;

    public Task ShowAsync(string title, string message, CancellationToken cancellationToken = default)
        => PublishAsync(
            title,
            message,
            Classify(title),
            showDesktopNotification: true,
            cancellationToken: cancellationToken);

    public async Task PublishAsync(
        string title,
        string message,
        NotificationCenterSeverity severity = NotificationCenterSeverity.Information,
        string? downloadId = null,
        bool showDesktopNotification = true,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(title);
        ArgumentException.ThrowIfNullOrWhiteSpace(message);

        NotificationCenterEntry entry = new(
            Guid.NewGuid().ToString("N"),
            DateTimeOffset.UtcNow,
            title.Trim(),
            message.Trim(),
            severity,
            string.IsNullOrWhiteSpace(downloadId) ? null : downloadId,
            showDesktopNotification);

        lock (_sync)
        {
            _entries.Insert(0, entry);
            if (_entries.Count > Capacity)
            {
                _entries.RemoveRange(Capacity, _entries.Count - Capacity);
            }
        }

        Changed?.Invoke(this, EventArgs.Empty);
        if (showDesktopNotification)
        {
            await _desktopNotifications.ShowAsync(title, message, cancellationToken).ConfigureAwait(false);
        }
    }

    public IReadOnlyList<NotificationCenterEntry> Snapshot()
    {
        lock (_sync)
        {
            return _entries.ToArray();
        }
    }

    public void Dismiss(string id)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(id);
        lock (_sync)
        {
            _entries.RemoveAll(entry => string.Equals(entry.Id, id, StringComparison.Ordinal));
        }

        Changed?.Invoke(this, EventArgs.Empty);
    }

    public void Clear()
    {
        lock (_sync)
        {
            _entries.Clear();
        }

        Changed?.Invoke(this, EventArgs.Empty);
    }

    private static NotificationCenterSeverity Classify(string title)
        => title.Contains("failed", StringComparison.OrdinalIgnoreCase)
            || title.Contains("error", StringComparison.OrdinalIgnoreCase)
                ? NotificationCenterSeverity.Error
                : title.Contains("completed", StringComparison.OrdinalIgnoreCase)
                    || title.Contains("ready", StringComparison.OrdinalIgnoreCase)
                        ? NotificationCenterSeverity.Success
                        : title.Contains("warning", StringComparison.OrdinalIgnoreCase)
                            ? NotificationCenterSeverity.Warning
                            : NotificationCenterSeverity.Information;
}
