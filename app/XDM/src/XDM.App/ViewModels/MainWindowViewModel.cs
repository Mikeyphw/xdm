using System.Collections.ObjectModel;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using XDM.Core.Abstractions;
using XDM.Core.Downloads;
using XDM.Core.State;
using XDM.DownloadEngine;
using XDM.Platform;

namespace XDM.App.ViewModels;

public partial class MainWindowViewModel : ObservableObject, IDisposable
{
    private readonly IApplicationState _applicationState;
    private readonly IDownloadManager _downloadManager;
    private readonly IUiDispatcher _dispatcher;
    private bool _disposed;

    public MainWindowViewModel(
        IApplicationState applicationState,
        IPlatformInfo platformInfo,
        IDownloadManager downloadManager,
        IUiDispatcher dispatcher)
    {
        _applicationState = applicationState;
        _downloadManager = downloadManager;
        _dispatcher = dispatcher;
        PlatformDescription = platformInfo.DisplayName;
        RuntimeDescription = platformInfo.Runtime;
        DestinationFolder = GetDefaultDownloadFolder();

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

    public ObservableCollection<DownloadItemViewModel> Downloads { get; } = [];

    public string PlatformDescription { get; }

    public string RuntimeDescription { get; }

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(IsDownloadsVisible))]
    private NavigationItem? selectedSection;

    [ObservableProperty]
    private DownloadItemViewModel? selectedDownload;

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

    [ObservableProperty]
    private string newDownloadUrl = string.Empty;

    [ObservableProperty]
    private string destinationFolder = string.Empty;

    [ObservableProperty]
    private string operationMessage = "Ready";

    public bool IsDownloadsVisible
        => string.Equals(SelectedSection?.Title, "Downloads", StringComparison.Ordinal);

    partial void OnSelectedSectionChanged(NavigationItem? value)
    {
        CurrentTitle = value?.Title ?? "Downloads";
        CurrentSummary = value?.Summary ?? string.Empty;
    }

    [RelayCommand]
    private async Task AddDownloadAsync()
    {
        if (!Uri.TryCreate(NewDownloadUrl.Trim(), UriKind.Absolute, out Uri? source)
            || source.Scheme is not ("http" or "https"))
        {
            OperationMessage = "Enter a valid HTTP or HTTPS URL.";
            return;
        }

        if (string.IsNullOrWhiteSpace(DestinationFolder))
        {
            OperationMessage = "Choose a destination folder.";
            return;
        }

        try
        {
            await _downloadManager.AddAsync(new DownloadRequest(source, DestinationFolder));
            NewDownloadUrl = string.Empty;
            OperationMessage = "Download added.";
        }
        catch (ArgumentException exception)
        {
            OperationMessage = exception.Message;
        }
        catch (IOException exception)
        {
            OperationMessage = exception.Message;
        }
        catch (UnauthorizedAccessException exception)
        {
            OperationMessage = exception.Message;
        }
    }

    [RelayCommand]
    private Task PauseSelectedAsync()
        => SelectedDownload is null
            ? Task.CompletedTask
            : _downloadManager.PauseAsync(SelectedDownload.Id);

    [RelayCommand]
    private Task ResumeSelectedAsync()
        => SelectedDownload is null
            ? Task.CompletedTask
            : _downloadManager.ResumeAsync(SelectedDownload.Id);

    [RelayCommand]
    private Task CancelSelectedAsync()
        => SelectedDownload is null
            ? Task.CompletedTask
            : _downloadManager.CancelAsync(SelectedDownload.Id);

    [RelayCommand]
    private Task RetrySelectedAsync()
        => SelectedDownload is null
            ? Task.CompletedTask
            : _downloadManager.RetryAsync(SelectedDownload.Id);

    [RelayCommand]
    private async Task RemoveSelectedAsync()
    {
        if (SelectedDownload is null)
        {
            return;
        }

        string id = SelectedDownload.Id;
        await _downloadManager.RemoveAsync(id);
        OperationMessage = "Download removed from history.";
    }

    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }

        _disposed = true;
        _applicationState.Changed -= OnApplicationStateChanged;
        GC.SuppressFinalize(this);
    }

    private void OnApplicationStateChanged(object? sender, ApplicationSnapshot snapshot)
    {
        if (_dispatcher.CheckAccess())
        {
            ApplySnapshot(snapshot);
        }
        else
        {
            _dispatcher.Post(() => ApplySnapshot(snapshot));
        }
    }

    private void ApplySnapshot(ApplicationSnapshot snapshot)
    {
        CoreStatus = snapshot.CoreReady ? "Ready" : "Starting";
        ActiveDownloadCount = snapshot.ActiveDownloadCount;
        AggregateSpeed = FormatSpeed(snapshot.AggregateBytesPerSecond);

        Dictionary<string, DownloadItemViewModel> existing = Downloads
            .ToDictionary(static item => item.Id, StringComparer.Ordinal);

        foreach (DownloadSnapshot download in snapshot.Downloads)
        {
            if (existing.Remove(download.Id, out DownloadItemViewModel? item))
            {
                item.Apply(download);
            }
            else
            {
                Downloads.Add(new DownloadItemViewModel(download));
            }
        }

        foreach (DownloadItemViewModel removed in existing.Values)
        {
            Downloads.Remove(removed);
        }
    }

    private static string GetDefaultDownloadFolder()
    {
        string userProfile = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        string downloads = Path.Combine(userProfile, "Downloads");
        return Directory.Exists(downloads) ? downloads : userProfile;
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
