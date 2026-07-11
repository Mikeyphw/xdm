namespace XDM.App.ViewModels;

public sealed record DownloadTimelineEntry(DateTimeOffset Timestamp, string Status, string Message);
