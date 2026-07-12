using XDM.Core.Localization;
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
    DownloadSchedulerSettings Scheduler,
    IReadOnlyList<QueueScheduleDefinition>? Schedules = null,
    AntivirusScanSettings? Antivirus = null,
    NetworkSettings? Network = null,
    DownloadBehaviorSettings? DownloadBehavior = null,
    IReadOnlyList<ServerCredentialDefinition>? Credentials = null,
    HistoryRetentionSettings? History = null,
    LocalizationSettings? Localization = null,
    AccessibilitySettings? Accessibility = null,
    Aria2IntegrationSettings? Aria2 = null)
{
    public const int CurrentSchemaVersion = 5;

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
            new DownloadSchedulerSettings(false, "default", new TimeOnly(0, 0), new TimeOnly(23, 59), WeekDays.EveryDay),
            [new QueueScheduleDefinition(
                "default-schedule",
                "Default schedule",
                false,
                "default",
                new TimeOnly(0, 0),
                new TimeOnly(23, 59),
                WeekDays.EveryDay,
                MissedRunPolicy.Skip,
                ScheduleCompletionAction.None)],
            AntivirusScanSettings.Disabled,
            NetworkSettings.Default,
            DownloadBehaviorSettings.Default,
            [],
            HistoryRetentionSettings.Default,
            LocalizationSettings.Default,
            AccessibilitySettings.Default,
            Aria2IntegrationSettings.Default);
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
        QueueScheduleDefinition[] schedules = Schedules?
            .Select(schedule => schedule.Normalize(schedulerQueue))
            .Where(schedule => queues.Any(queue => string.Equals(queue.Id, schedule.QueueId, StringComparison.Ordinal)))
            .DistinctBy(static schedule => schedule.Id, StringComparer.Ordinal)
            .Take(64)
            .ToArray() ?? [];
        if (schedules.Length == 0)
        {
            schedules =
            [
                new QueueScheduleDefinition(
                    "legacy-schedule",
                    "Imported schedule",
                    scheduler.Enabled,
                    schedulerQueue,
                    scheduler.StartTime,
                    scheduler.EndTime,
                    scheduler.Days,
                    MissedRunPolicy.Skip,
                    ScheduleCompletionAction.None)
            ];
        }

        QueueScheduleDefinition primarySchedule = schedules[0];
        ServerCredentialDefinition[] credentials = Credentials?
            .Select(static credential => credential.Normalize())
            .Where(static credential => credential.Host.Length > 0 && credential.Username.Length > 0)
            .DistinctBy(static credential => credential.Host, StringComparer.OrdinalIgnoreCase)
            .Take(256)
            .ToArray() ?? [];
        return this with
        {
            SchemaVersion = CurrentSchemaVersion,
            DefaultDownloadDirectory = defaultDirectory,
            MaxConcurrentDownloads = maxConcurrent,
            DefaultSpeedLimitBytesPerSecond = speedLimit,
            Categories = categories,
            Queues = queues,
            Scheduler = new DownloadSchedulerSettings(
                primarySchedule.Enabled,
                primarySchedule.QueueId,
                primarySchedule.StartTime,
                primarySchedule.EndTime,
                primarySchedule.Days),
            Schedules = schedules,
            Antivirus = (Antivirus ?? AntivirusScanSettings.Disabled).Normalize(),
            Network = (Network ?? NetworkSettings.Default).Normalize(),
            DownloadBehavior = (DownloadBehavior ?? DownloadBehaviorSettings.Default).Normalize(),
            Credentials = credentials,
            History = (History ?? HistoryRetentionSettings.Default).Normalize(),
            Localization = (Localization ?? LocalizationSettings.Default).Normalize(),
            Accessibility = (Accessibility ?? AccessibilitySettings.Default).Normalize(),
            Aria2 = (Aria2 ?? Aria2IntegrationSettings.Default).Normalize()
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
