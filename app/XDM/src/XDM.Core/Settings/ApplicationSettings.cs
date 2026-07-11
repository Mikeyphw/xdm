using XDM.Core.Scheduling;

namespace XDM.Core.Settings;

public sealed record ApplicationSettings(
    int SchemaVersion,
    string DefaultDownloadDirectory,
    int MaxConcurrentDownloads,
    long DefaultSpeedLimitBytesPerSecond,
    bool ClipboardMonitoringEnabled,
    bool AutoAddClipboardLinks,
    IReadOnlyList<DownloadCategoryDefinition> Categories,
    IReadOnlyList<DownloadQueueDefinition> Queues,
    DownloadSchedulerSettings Scheduler)
{
    public const int CurrentSchemaVersion = 1;

    public static ApplicationSettings CreateDefault()
    {
        string userProfile = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        string downloads = Path.Combine(userProfile, "Downloads");
        string defaultDirectory = Directory.Exists(downloads) ? downloads : userProfile;

        return new ApplicationSettings(
            CurrentSchemaVersion,
            defaultDirectory,
            4,
            0,
            false,
            false,
            [
                new DownloadCategoryDefinition("general", "General", [], defaultDirectory),
                new DownloadCategoryDefinition("archives", "Archives", ["zip", "7z", "rar", "tar.gz"], defaultDirectory),
                new DownloadCategoryDefinition("video", "Video", ["mp4", "mkv", "webm"], defaultDirectory)
            ],
            [new DownloadQueueDefinition("default", "Default", 4, 0)],
            new DownloadSchedulerSettings(false, "default", new TimeOnly(0, 0), new TimeOnly(23, 59), WeekDays.EveryDay));
    }

    public ApplicationSettings Normalize()
    {
        string defaultDirectory = string.IsNullOrWhiteSpace(DefaultDownloadDirectory)
            ? CreateDefault().DefaultDownloadDirectory
            : DefaultDownloadDirectory;
        int maxConcurrent = Math.Clamp(MaxConcurrentDownloads, 1, 32);
        long speedLimit = Math.Max(0, DefaultSpeedLimitBytesPerSecond);
        DownloadCategoryDefinition[] categories = Categories?
            .Where(static category => !string.IsNullOrWhiteSpace(category.Id) && !string.IsNullOrWhiteSpace(category.Name))
            .Select(category => category.Normalize(defaultDirectory))
            .DistinctBy(static category => category.Id, StringComparer.Ordinal)
            .ToArray() ?? [];
        DownloadQueueDefinition[] queues = Queues?
            .Where(static queue => !string.IsNullOrWhiteSpace(queue.Id) && !string.IsNullOrWhiteSpace(queue.Name))
            .Select(static queue => queue.Normalize())
            .DistinctBy(static queue => queue.Id, StringComparer.Ordinal)
            .ToArray() ?? [];

        if (categories.Length == 0)
        {
            categories = [new DownloadCategoryDefinition("general", "General", [], defaultDirectory)];
        }

        if (queues.Length == 0)
        {
            queues = [new DownloadQueueDefinition("default", "Default", maxConcurrent, speedLimit)];
        }

        DownloadSchedulerSettings scheduler = Scheduler ?? CreateDefault().Scheduler;
        string schedulerQueue = queues.Any(queue => string.Equals(queue.Id, scheduler.QueueId, StringComparison.Ordinal))
            ? scheduler.QueueId
            : queues[0].Id;

        return this with
        {
            SchemaVersion = CurrentSchemaVersion,
            DefaultDownloadDirectory = defaultDirectory,
            MaxConcurrentDownloads = maxConcurrent,
            DefaultSpeedLimitBytesPerSecond = speedLimit,
            Categories = categories,
            Queues = queues,
            Scheduler = scheduler with { QueueId = schedulerQueue }
        };
    }
}

public sealed record DownloadCategoryDefinition(
    string Id,
    string Name,
    IReadOnlyList<string> Extensions,
    string DestinationDirectory)
{
    public DownloadCategoryDefinition Normalize(string fallbackDirectory)
        => this with
        {
            Id = Id.Trim(),
            Name = Name.Trim(),
            Extensions = Extensions?
                .Select(static extension => extension.Trim().TrimStart('.').ToUpperInvariant())
                .Where(static extension => extension.Length > 0)
                .Distinct(StringComparer.OrdinalIgnoreCase)
                .ToArray() ?? [],
            DestinationDirectory = string.IsNullOrWhiteSpace(DestinationDirectory)
                ? fallbackDirectory
                : DestinationDirectory
        };
}

public sealed record DownloadQueueDefinition(
    string Id,
    string Name,
    int MaxConcurrentDownloads,
    long SpeedLimitBytesPerSecond)
{
    public DownloadQueueDefinition Normalize()
        => this with
        {
            Id = Id.Trim(),
            Name = Name.Trim(),
            MaxConcurrentDownloads = Math.Clamp(MaxConcurrentDownloads, 1, 32),
            SpeedLimitBytesPerSecond = Math.Max(0, SpeedLimitBytesPerSecond)
        };
}

public sealed record DownloadSchedulerSettings(
    bool Enabled,
    string QueueId,
    TimeOnly StartTime,
    TimeOnly EndTime,
    WeekDays Days);
