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
    private CancellationTokenSource? _updateCancellation;

    [ObservableProperty]
    private string updateStatus = $"Current version: {ProductVersion.Current}";

    [ObservableProperty]
    private double updateProgress;

    [ObservableProperty]
    private bool updateOperationActive;

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
            _availableUpdate = await _updateService.CheckAsync(cancellation.Token);
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
            _stagedUpdatePath = result.PackagePath;
            UpdateProgress = 1;
            UpdateStatus = $"Verified XDM {result.Version} package staged at {result.PackagePath}.";
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
