using System.Collections.ObjectModel;
using CommunityToolkit.Mvvm.ComponentModel;

namespace XDM.App.ViewModels;

public partial class MainWindowViewModel : ObservableObject
{
    public MainWindowViewModel()
    {
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
    }

    public ObservableCollection<NavigationItem> Sections { get; }

    [ObservableProperty]
    private NavigationItem? selectedSection;

    [ObservableProperty]
    private string currentTitle = "Downloads";

    [ObservableProperty]
    private string currentSummary = "Active, queued, completed, and failed downloads.";

    partial void OnSelectedSectionChanged(NavigationItem? value)
    {
        CurrentTitle = value?.Title ?? "Downloads";
        CurrentSummary = value?.Summary ?? string.Empty;
    }
}
