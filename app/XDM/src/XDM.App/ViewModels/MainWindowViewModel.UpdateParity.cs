using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using XDM.Core.Abstractions;
using XDM.Core.Product;

namespace XDM.App.ViewModels;

public partial class MainWindowViewModel
{
    private readonly IUpdateService _updateService;
    private UpdateCheckResult? _availableUpdate;
    private string? _stagedUpdatePath;
    private StagedUpdateResult? _stagedUpdate;
    private CancellationTokenSource? _updateCancellation;

    public IReadOnlyList<UpdateChannel> UpdateChannels { get; } = Enum.GetValues<UpdateChannel>();

    [ObservableProperty]
    private UpdateChannel selectedUpdateChannel = UpdateChannel.Stable;

    [ObservableProperty]
    private bool automaticUpdateChecks = true;

    [ObservableProperty]
    private bool notifyWhenUpdateStaged = true;

    [ObservableProperty]
    private string updateStatus = $"Current version: {ProductVersion.Current}";

    [ObservableProperty]
    private double updateProgress;

    [ObservableProperty]
    private bool updateOperationActive;

    public async Task InitializeAutomaticUpdateCheckAsync()
    {
        if (!AutomaticUpdateChecks)
        {
            return;
        }

        await CheckForUpdatesAsync();
    }

    [RelayCommand]
    private async Task CheckForUpdatesAsync()
    {
        if (UpdateOperationActive)
        {
            return;
        }

        UpdateOperationActive = true;
        UpdateProgress = 0;
        using CancellationTokenSource cancellation = new();
        _updateCancellation = cancellation;
        try
        {
            _availableUpdate = await _updateService.CheckAsync(SelectedUpdateChannel, cancellation.Token);
            UpdateStatus = _availableUpdate.Message;
        }
        catch (OperationCanceledException)
        {
            UpdateStatus = "Update check cancelled.";
        }
        catch (HttpRequestException exception)
        {
            UpdateStatus = $"Update check failed: {exception.Message}";
        }
        catch (InvalidDataException exception)
        {
            UpdateStatus = $"Update manifest rejected: {exception.Message}";
        }
        catch (ObjectDisposedException)
        {
            UpdateStatus = "Update check stopped because XDM is closing.";
        }
        finally
        {
            if (ReferenceEquals(_updateCancellation, cancellation))
            {
                _updateCancellation = null;
            }
            UpdateOperationActive = false;
        }
    }

    [RelayCommand]
    private async Task DownloadVerifiedUpdateAsync()
    {
        if (UpdateOperationActive || _availableUpdate is not { UpdateAvailable: true, Package: not null })
        {
            UpdateStatus = "Check for a compatible update first.";
            return;
        }

        UpdateOperationActive = true;
        UpdateProgress = 0;
        using CancellationTokenSource cancellation = new();
        _updateCancellation = cancellation;
        Progress<double> progress = new(value => UpdateProgress = Math.Clamp(value, 0, 1));
        try
        {
            StagedUpdateResult result = await _updateService.StageAsync(
                _availableUpdate,
                progress,
                cancellation.Token);
            _stagedUpdate = result;
            _stagedUpdatePath = result.PackagePath;
            UpdateProgress = 1;
            UpdateStatus = $"Verified XDM {result.Version} package staged. Receipt: {result.ReceiptPath}. Rollback transaction: {result.TransactionPath}.";
            if (NotifyWhenUpdateStaged)
            {
                _ = _desktopNotifications.ShowAsync(
                    "XDM update ready",
                    $"XDM {result.Version} is verified and ready to apply.");
            }
        }
        catch (OperationCanceledException)
        {
            UpdateStatus = "Update download cancelled.";
        }
        catch (HttpRequestException exception)
        {
            UpdateStatus = $"Update download failed: {exception.Message}";
        }
        catch (InvalidDataException exception)
        {
            UpdateStatus = $"Update package rejected: {exception.Message}";
        }
        catch (UnauthorizedAccessException exception)
        {
            UpdateStatus = $"Update package could not be written: {exception.Message}";
        }
        catch (IOException exception)
        {
            UpdateStatus = $"Update package could not be written: {exception.Message}";
        }
        finally
        {
            if (ReferenceEquals(_updateCancellation, cancellation))
            {
                _updateCancellation = null;
            }
            UpdateOperationActive = false;
        }
    }

    [RelayCommand]
    private async Task ApplyStagedUpdateAsync()
    {
        if (_stagedUpdate is null || string.IsNullOrWhiteSpace(_stagedUpdate.TransactionPath))
        {
            UpdateStatus = "No rollback-safe update transaction has been staged.";
            return;
        }

        try
        {
            await _updateService.LaunchStagedUpdateAsync(_stagedUpdate, Environment.ProcessId);
            UpdateStatus = "Updater launched. XDM will close and restart after the verified directory swap.";
            await _applicationLifetimeService.RequestShutdownAsync();
        }
        catch (FileNotFoundException exception)
        {
            UpdateStatus = $"Automatic update is unavailable: {exception.Message}";
        }
        catch (InvalidOperationException exception)
        {
            UpdateStatus = $"Automatic update could not start: {exception.Message}";
        }
        catch (UnauthorizedAccessException exception)
        {
            UpdateStatus = $"Automatic update needs permission to modify this installation: {exception.Message}";
        }
        catch (IOException exception)
        {
            UpdateStatus = $"Automatic update could not prepare its external runner: {exception.Message}";
        }
        catch (System.ComponentModel.Win32Exception exception)
        {
            UpdateStatus = $"Automatic update could not launch its external runner: {exception.Message}";
        }
    }

    [RelayCommand]
    private async Task OpenStagedUpdateAsync()
    {
        if (string.IsNullOrWhiteSpace(_stagedUpdatePath) || !File.Exists(_stagedUpdatePath))
        {
            UpdateStatus = "No verified update package has been staged.";
            return;
        }

        await _platformService.RevealFileAsync(_stagedUpdatePath);
    }

    [RelayCommand]
    private void CancelUpdateOperation()
        => _updateCancellation?.Cancel();
}
