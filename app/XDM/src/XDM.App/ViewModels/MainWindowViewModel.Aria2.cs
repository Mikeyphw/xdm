using System.Collections.ObjectModel;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using XDM.Core.Settings;
using XDM.DownloadEngine.Aria2;

namespace XDM.App.ViewModels;

public partial class MainWindowViewModel
{
    private readonly IAria2Service _aria2Service;

    public IReadOnlyList<Aria2ConnectionMode> Aria2ConnectionModes { get; } = Enum.GetValues<Aria2ConnectionMode>();

    public ObservableCollection<Aria2TaskViewModel> Aria2Tasks { get; } = [];

    [ObservableProperty]
    private bool aria2Enabled;

    [ObservableProperty]
    private Aria2ConnectionMode selectedAria2ConnectionMode = Aria2ConnectionMode.ManagedProcess;

    [ObservableProperty]
    private string aria2RpcEndpoint = Aria2IntegrationSettings.Default.RpcEndpoint;

    [ObservableProperty]
    private string aria2RpcSecret = string.Empty;

    [ObservableProperty]
    private string aria2ExecutablePath = Aria2IntegrationSettings.Default.ExecutablePath;

    [ObservableProperty]
    private string aria2SessionFilePath = Aria2IntegrationSettings.Default.SessionFilePath;

    [ObservableProperty]
    private string aria2PollIntervalMilliseconds = "1000";

    [ObservableProperty]
    private string aria2RpcConnectTimeoutSeconds = "5";

    [ObservableProperty]
    private string aria2MaxConcurrentDownloads = "5";

    [ObservableProperty]
    private string aria2SplitCount = "8";

    [ObservableProperty]
    private string aria2MinimumSplitSizeMegabytes = "1";

    [ObservableProperty]
    private string aria2AdditionalArguments = string.Empty;

    [ObservableProperty]
    private bool aria2AutoStartManagedProcess = true;

    [ObservableProperty]
    private bool aria2ContinueDownloads = true;

    [ObservableProperty]
    private bool aria2CheckCertificate = true;

    [ObservableProperty]
    private bool aria2SaveSession = true;

    [ObservableProperty]
    private string aria2NewUrl = string.Empty;

    [ObservableProperty]
    private string aria2DestinationFolder = string.Empty;

    [ObservableProperty]
    private string aria2CustomFileName = string.Empty;

    [ObservableProperty]
    private Aria2TaskViewModel? selectedAria2Task;

    [ObservableProperty]
    private string aria2ConnectionStatus = "aria2 integration is disabled.";

    [ObservableProperty]
    private bool aria2IsRefreshing;

    public bool CanStartManagedAria2
        => Aria2Enabled && SelectedAria2ConnectionMode == Aria2ConnectionMode.ManagedProcess;

    partial void OnAria2EnabledChanged(bool value)
        => OnPropertyChanged(nameof(CanStartManagedAria2));

    partial void OnSelectedAria2ConnectionModeChanged(Aria2ConnectionMode value)
        => OnPropertyChanged(nameof(CanStartManagedAria2));

    [RelayCommand]
    private async Task ApplyAria2ConfigurationAsync()
    {
        try
        {
            Aria2IntegrationSettings settings = BuildAria2Settings();
            await _settingsService.UpdateAsync(_settingsService.Current with { Aria2 = settings });
            await _aria2Service.ConfigureAsync(settings);
            OperationMessage = "aria2 configuration applied.";
        }
        catch (Exception exception) when (IsExpectedAria2Exception(exception))
        {
            OperationMessage = exception.Message;
        }
    }

    [RelayCommand]
    private async Task StartManagedAria2Async()
    {
        try
        {
            Aria2IntegrationSettings settings = BuildAria2Settings() with
            {
                Enabled = true,
                ConnectionMode = Aria2ConnectionMode.ManagedProcess
            };
            Aria2Enabled = true;
            SelectedAria2ConnectionMode = Aria2ConnectionMode.ManagedProcess;
            await _settingsService.UpdateAsync(_settingsService.Current with { Aria2 = settings });
            await _aria2Service.ConfigureAsync(settings);
            await _aria2Service.StartManagedProcessAsync();
            OperationMessage = "Managed aria2 started.";
        }
        catch (Exception exception) when (IsExpectedAria2Exception(exception))
        {
            OperationMessage = exception.Message;
        }
    }

    [RelayCommand]
    private async Task StopManagedAria2Async()
    {
        try
        {
            await _aria2Service.StopManagedProcessAsync();
            OperationMessage = "Managed aria2 stopped.";
        }
        catch (Exception exception) when (IsExpectedAria2Exception(exception))
        {
            OperationMessage = exception.Message;
        }
    }

    [RelayCommand]
    private async Task RefreshAria2Async()
    {
        try
        {
            await _aria2Service.RefreshAsync();
            OperationMessage = "aria2 status refreshed.";
        }
        catch (Exception exception) when (IsExpectedAria2Exception(exception))
        {
            OperationMessage = exception.Message;
        }
    }

    [RelayCommand]
    private async Task AddAria2DownloadAsync()
    {
        if (!Uri.TryCreate(Aria2NewUrl.Trim(), UriKind.Absolute, out Uri? source))
        {
            OperationMessage = "Enter a valid absolute URL or magnet URI for aria2.";
            return;
        }

        try
        {
            string destination = string.IsNullOrWhiteSpace(Aria2DestinationFolder)
                ? DestinationFolder
                : Aria2DestinationFolder;
            string gid = await _aria2Service.AddAsync(new Aria2AddRequest(
                source,
                destination,
                string.IsNullOrWhiteSpace(Aria2CustomFileName) ? null : Aria2CustomFileName.Trim()));
            Aria2NewUrl = string.Empty;
            Aria2CustomFileName = string.Empty;
            OperationMessage = $"aria2 task {gid} added.";
        }
        catch (Exception exception) when (IsExpectedAria2Exception(exception))
        {
            OperationMessage = exception.Message;
        }
    }

    [RelayCommand]
    private async Task PauseSelectedAria2TaskAsync()
    {
        if (SelectedAria2Task is null)
        {
            return;
        }

        await RunSelectedAria2CommandAsync(
            (gid, cancellationToken) => _aria2Service.PauseAsync(gid, cancellationToken),
            "aria2 pause requested.");
    }

    [RelayCommand]
    private async Task ResumeSelectedAria2TaskAsync()
    {
        if (SelectedAria2Task is null)
        {
            return;
        }

        await RunSelectedAria2CommandAsync(
            (gid, cancellationToken) => _aria2Service.ResumeAsync(gid, cancellationToken),
            "aria2 resume requested.");
    }

    [RelayCommand]
    private async Task RemoveSelectedAria2TaskAsync()
    {
        if (SelectedAria2Task is null)
        {
            return;
        }

        await RunSelectedAria2CommandAsync(
            (gid, cancellationToken) => _aria2Service.RemoveAsync(gid, cancellationToken),
            "aria2 removal requested.");
    }

    private async Task RunSelectedAria2CommandAsync(
        Func<string, CancellationToken, Task> command,
        string successMessage)
    {
        string? gid = SelectedAria2Task?.Gid;
        if (gid is null)
        {
            return;
        }

        try
        {
            await command(gid, CancellationToken.None);
            OperationMessage = successMessage;
        }
        catch (Exception exception) when (IsExpectedAria2Exception(exception))
        {
            OperationMessage = exception.Message;
        }
    }

    private Aria2IntegrationSettings BuildAria2Settings()
    {
        Aria2IntegrationSettings current = _settingsService.Current.Aria2 ?? Aria2IntegrationSettings.Default;
        double minimumSplitMiB = ParseDouble(Aria2MinimumSplitSizeMegabytes, 1d);
        return new Aria2IntegrationSettings(
            Aria2Enabled,
            SelectedAria2ConnectionMode,
            Aria2RpcEndpoint,
            Aria2RpcSecret,
            Aria2ExecutablePath,
            Aria2SessionFilePath,
            ParseInteger(Aria2PollIntervalMilliseconds, current.PollIntervalMilliseconds),
            ParseInteger(Aria2RpcConnectTimeoutSeconds, current.RpcConnectTimeoutSeconds),
            ParseInteger(Aria2MaxConcurrentDownloads, current.MaxConcurrentDownloads),
            ParseInteger(Aria2SplitCount, current.SplitCount),
            checked((long)(Math.Max(1d, minimumSplitMiB) * 1024 * 1024)),
            Aria2AdditionalArguments,
            Aria2AutoStartManagedProcess,
            Aria2ContinueDownloads,
            Aria2CheckCertificate,
            Aria2SaveSession).Normalize();
    }

    private void ApplyAria2Settings(ApplicationSettings settings)
    {
        Aria2IntegrationSettings aria2 = (settings.Aria2 ?? Aria2IntegrationSettings.Default).Normalize();
        Aria2Enabled = aria2.Enabled;
        SelectedAria2ConnectionMode = aria2.ConnectionMode;
        Aria2RpcEndpoint = aria2.RpcEndpoint;
        Aria2RpcSecret = aria2.RpcSecret;
        Aria2ExecutablePath = aria2.ExecutablePath;
        Aria2SessionFilePath = aria2.SessionFilePath;
        Aria2PollIntervalMilliseconds = aria2.PollIntervalMilliseconds.ToString(System.Globalization.CultureInfo.InvariantCulture);
        Aria2RpcConnectTimeoutSeconds = aria2.RpcConnectTimeoutSeconds.ToString(System.Globalization.CultureInfo.InvariantCulture);
        Aria2MaxConcurrentDownloads = aria2.MaxConcurrentDownloads.ToString(System.Globalization.CultureInfo.InvariantCulture);
        Aria2SplitCount = aria2.SplitCount.ToString(System.Globalization.CultureInfo.InvariantCulture);
        Aria2MinimumSplitSizeMegabytes = (aria2.MinimumSplitSizeBytes / 1024d / 1024d)
            .ToString("0.###", System.Globalization.CultureInfo.InvariantCulture);
        Aria2AdditionalArguments = aria2.AdditionalArguments;
        Aria2AutoStartManagedProcess = aria2.AutoStartManagedProcess;
        Aria2ContinueDownloads = aria2.ContinueDownloads;
        Aria2CheckCertificate = aria2.CheckCertificate;
        Aria2SaveSession = aria2.SaveSession;
        if (string.IsNullOrWhiteSpace(Aria2DestinationFolder))
        {
            Aria2DestinationFolder = settings.DefaultDownloadDirectory;
        }
    }

    private void OnAria2SnapshotChanged(object? sender, Aria2ServiceSnapshot snapshot)
    {
        if (_dispatcher.CheckAccess())
        {
            ApplyAria2Snapshot(snapshot);
        }
        else
        {
            _dispatcher.Post(() => ApplyAria2Snapshot(snapshot));
        }
    }

    private void ApplyAria2Snapshot(Aria2ServiceSnapshot snapshot)
    {
        string? selectedGid = SelectedAria2Task?.Gid;
        Dictionary<string, Aria2TaskViewModel> existing = Aria2Tasks.ToDictionary(
            static task => task.Gid,
            StringComparer.Ordinal);
        foreach (Aria2TaskSnapshot task in snapshot.Tasks)
        {
            if (existing.Remove(task.Gid, out Aria2TaskViewModel? viewModel))
            {
                viewModel.Apply(task, _localization);
            }
            else
            {
                Aria2Tasks.Add(new Aria2TaskViewModel(task, _localization));
            }
        }

        foreach (Aria2TaskViewModel stale in existing.Values)
        {
            Aria2Tasks.Remove(stale);
        }

        SelectedAria2Task = Aria2Tasks.FirstOrDefault(task => string.Equals(task.Gid, selectedGid, StringComparison.Ordinal))
            ?? (Aria2Tasks.Count > 0 ? Aria2Tasks[0] : null);
        Aria2ConnectionStatus = snapshot.Health.Message;
        Aria2IsRefreshing = snapshot.IsRefreshing;
    }

    private void RefreshAria2Localization()
    {
        foreach (Aria2TaskViewModel task in Aria2Tasks)
        {
            task.RefreshLocalization(_localization);
        }
    }

    private static bool IsExpectedAria2Exception(Exception exception)
        => exception is ArgumentException
            or InvalidOperationException
            or NotSupportedException
            or HttpRequestException
            or IOException
            or UnauthorizedAccessException
            or Aria2RpcException;
}
