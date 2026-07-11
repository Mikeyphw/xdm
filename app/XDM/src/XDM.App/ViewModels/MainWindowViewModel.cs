using System.Collections.ObjectModel;
using CommunityToolkit.Mvvm.ComponentModel;
using XDM.Core.State;
using XDM.Platform;

namespace XDM.App.ViewModels;

public partial class MainWindowViewModel : ObservableObject
{
    public MainWindowViewModel(IApplicationState applicationState, IPlatformInfo platformInfo)
    {
        PlatformDescription = platformInfo.DisplayName;
        RuntimeDescription = platformInfo.Runtime;

        Sections = new ObservableCollection<NavigationItem>
        {
            new("Downloads", "↓", "Active, queued, completed, and failed downloads."),
            new("Queues", "≡", "Concurrency, ordering, and bandwidth policies."),
            new("Scheduler", "◷", "Time windows and unattended download runs."),
            new("Browser Integration", "◉", "Extension, native host, and capture health."),
            new("Settings", "⚙", "Folders, network, proxy, appearance, and behavior."),
            new("Diagnostics", "◇", "Startup, runtime, browser, and engine diagnostics.")
        };

        SelectedSection = Sections[0];
        ApplySnapshot(applicationState.Current);
        applicationState.Changed += OnApplicationStateChanged;
    }

    public ObservableCollection<NavigationItem> Sections { get; }

    public string PlatformDescription { get; }

    public string RuntimeDescription { get; }

    [ObservableProperty]
    private NavigationItem? selectedSection;

    [ObservableProperty]
    private string currentTitle = "Downloads";

    [ObservableProperty]
    private string currentSummary = "Active, queued, completed, and failed downloads.";

    [ObservableProperty]
    private string coreStatus = "Starting";

    [ObservableProperty]
    private int activeDownloadCount;

    [ObservableProperty]
    private string aggregateSpeed = "0 B/s";

    partial void OnSelectedSectionChanged(NavigationItem? value)
    {
        CurrentTitle = value?.Title ?? "Downloads";
        CurrentSummary = value?.Summary ?? string.Empty;
    }

    private void OnApplicationStateChanged(object? sender, ApplicationSnapshot snapshot)
        => ApplySnapshot(snapshot);

    private void ApplySnapshot(ApplicationSnapshot snapshot)
    {
        CoreStatus = snapshot.CoreReady ? "Ready" : "Starting";
        ActiveDownloadCount = snapshot.ActiveDownloadCount;
        AggregateSpeed = FormatSpeed(snapshot.AggregateBytesPerSecond);
    }

    private static string FormatSpeed(double bytesPerSecond)
    {
        string[] units = ["B/s", "KB/s", "MB/s", "GB/s"];
        int unitIndex = 0;

        while (bytesPerSecond >= 1024 && unitIndex < units.Length - 1)
        {
            bytesPerSecond /= 1024;
            unitIndex++;
        }

        return $"{bytesPerSecond:0.#} {units[unitIndex]}";
    }
}
