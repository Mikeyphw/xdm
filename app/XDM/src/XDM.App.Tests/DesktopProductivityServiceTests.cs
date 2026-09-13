using XDM.App.Services;
using XDM.Core.Abstractions;
using XDM.Platform;
using Xunit;

namespace XDM.App.Tests;

public sealed class DesktopProductivityServiceTests
{
    private static readonly string[] ExpectedMutedDownloadIds = ["download-1", "download-2"];

    [Fact]
    public async Task StateStoreRoundTripsNormalizedPreferences()
    {
        using TemporaryDirectory directory = new();
        string path = Path.Combine(directory.Path, "productivity.json");
        using DesktopProductivityStateStore store = new(path);
        DesktopProductivityState state = new(
            DownloadListDensity.Compact,
            5_000,
            false,
            true,
            false,
            true,
            false,
            true,
            [" download-1 ", "download-1", "download-2"]);

        CancellationToken cancellationToken = TestContext.Current.CancellationToken;
        await store.SaveAsync(state, cancellationToken);
        DesktopProductivityState restored = await store.LoadAsync(cancellationToken);

        Assert.Equal(DownloadListDensity.Compact, restored.DownloadDensity);
        Assert.Equal(760d, restored.DownloadDetailsWidth);
        Assert.False(restored.ShowSource);
        Assert.Equal(ExpectedMutedDownloadIds, restored.MutedDownloadIds);
    }

    [Fact]
    public async Task NotificationCenterRetainsDismissesAndBoundsEntries()
    {
        CancellationToken cancellationToken = TestContext.Current.CancellationToken;
        NotificationCenterService center = new(new DesktopNotificationService());
        int changes = 0;
        center.Changed += (_, _) => changes++;
        for (int index = 0; index < 205; index++)
        {
            await center.PublishAsync(
                $"Notification {index}",
                "Message",
                showDesktopNotification: false,
                cancellationToken: cancellationToken);
        }

        IReadOnlyList<NotificationCenterEntry> snapshot = center.Snapshot();
        Assert.Equal(200, snapshot.Count);
        Assert.Equal("Notification 204", snapshot[0].Title);
        Assert.Equal("Notification 5", snapshot[^1].Title);

        center.Dismiss(snapshot[0].Id);
        Assert.Equal(199, center.Snapshot().Count);
        center.Clear();
        Assert.Empty(center.Snapshot());
        Assert.Equal(207, changes);
    }


    [Fact]
    public async Task NotificationCenterMarksDesktopShownOnlyAfterSuccessfulDelivery()
    {
        NotificationCenterService center = new(new DesktopNotificationService(
            new FixedNotificationCommandFactory(new NotificationCommand("notify-send", ["title", "message"], "test")),
            new FixedNotificationCommandExecutor(new NotificationCommandExecutionResult(0, string.Empty, string.Empty)),
            TimeSpan.FromSeconds(1)));

        DesktopNotificationDeliveryResult delivery = await center.PublishAsync(
            "Download completed",
            "file.zip",
            showDesktopNotification: true,
            cancellationToken: TestContext.Current.CancellationToken);

        NotificationCenterEntry entry = Assert.Single(center.Snapshot());
        Assert.True(delivery.Delivered);
        Assert.True(entry.DesktopNotificationShown);
        Assert.Null(entry.DesktopNotificationFailure);
    }

    [Fact]
    public async Task NotificationCenterKeepsShownFalseWhenDesktopDeliveryFails()
    {
        NotificationCenterService center = new(new DesktopNotificationService(
            new FixedNotificationCommandFactory(new NotificationCommand("notify-send", ["title", "message"], "test")),
            new FixedNotificationCommandExecutor(new NotificationCommandExecutionResult(1, string.Empty, "notify-send missing")),
            TimeSpan.FromSeconds(1)));

        DesktopNotificationDeliveryResult delivery = await center.PublishAsync(
            "Download completed",
            "file.zip",
            showDesktopNotification: true,
            cancellationToken: TestContext.Current.CancellationToken);

        NotificationCenterEntry entry = Assert.Single(center.Snapshot());
        Assert.False(delivery.Delivered);
        Assert.False(entry.DesktopNotificationShown);
        Assert.Equal("notify-send missing", entry.DesktopNotificationFailure);
    }

    private sealed class FixedNotificationCommandFactory(NotificationCommand? command) : IDesktopNotificationCommandFactory
    {
        public NotificationCommand? Create(string title, string message) => command;
    }

    private sealed class FixedNotificationCommandExecutor(NotificationCommandExecutionResult result) : INotificationCommandExecutor
    {
        public Task<NotificationCommandExecutionResult> ExecuteAsync(
            NotificationCommand command,
            CancellationToken cancellationToken = default)
            => Task.FromResult(result);
    }

    private sealed class TemporaryDirectory : IDisposable
    {
        public TemporaryDirectory()
        {
            Path = System.IO.Path.Combine(System.IO.Path.GetTempPath(), $"xdm-productivity-{Guid.NewGuid():N}");
            Directory.CreateDirectory(Path);
        }

        public string Path { get; }

        public void Dispose()
        {
            if (Directory.Exists(Path))
            {
                Directory.Delete(Path, recursive: true);
            }
        }
    }
}
