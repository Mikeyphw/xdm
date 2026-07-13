using System.Collections.ObjectModel;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using XDM.Core.Diagnostics;
using XDM.Diagnostics;

namespace XDM.App.ViewModels;

public partial class MainWindowViewModel
{
    private readonly ISubsystemHealthService _subsystemHealthService;
    private readonly IDeterministicDownloadTestService _deterministicDownloadTestService;

    public ObservableCollection<SubsystemHealthCheckViewModel> SubsystemHealthChecks { get; } = [];

    public ObservableCollection<DiagnosticKeyValueViewModel> SelectedTransferResponseHeaders { get; } = [];

    public ObservableCollection<TransferDiagnosticEntryViewModel> SelectedTransferRetryHistory { get; } = [];

    public ObservableCollection<TransferSegmentDiagnostic> SelectedTransferSegments { get; } = [];

    [ObservableProperty]
    private bool isSubsystemHealthRefreshing;

    [ObservableProperty]
    private string subsystemHealthStatus = "Run health checks to inspect browser, native host, aria2, FFmpeg, proxy, and destination disk.";

    [ObservableProperty]
    private bool isDeterministicDownloadTestRunning;

    [ObservableProperty]
    private string deterministicDownloadTestStatus = "The bounded test download has not been run.";

    [ObservableProperty]
    private string deterministicDownloadTestDetails = "Downloads exactly 1 MiB through the configured HTTP and proxy pipeline, without keeping the payload.";

    [ObservableProperty]
    private string selectedTransferResumeSummary = "Resume capability has not been evaluated for this transfer.";

    public bool CanRefreshSubsystemHealth => !IsSubsystemHealthRefreshing;

    public bool CanRunDeterministicDownloadTest => !IsDeterministicDownloadTestRunning;

    partial void OnIsSubsystemHealthRefreshingChanged(bool value)
        => OnPropertyChanged(nameof(CanRefreshSubsystemHealth));

    partial void OnIsDeterministicDownloadTestRunningChanged(bool value)
        => OnPropertyChanged(nameof(CanRunDeterministicDownloadTest));

    [RelayCommand]
    private async Task RefreshSubsystemHealthAsync()
    {
        if (IsSubsystemHealthRefreshing)
        {
            return;
        }

        IsSubsystemHealthRefreshing = true;
        SubsystemHealthStatus = "Running bounded subsystem health checks…";
        try
        {
            SubsystemHealthSnapshot snapshot = await _subsystemHealthService
                .RefreshAsync(GetDiagnosticsDestinationDirectory())
                .ConfigureAwait(false);
            await _dispatcher.InvokeAsync(() =>
            {
                ApplySubsystemHealth(snapshot);
                return Task.CompletedTask;
            });
        }
        catch (OperationCanceledException)
        {
            await _dispatcher.InvokeAsync(() =>
            {
                SubsystemHealthStatus = "Subsystem health checks were cancelled.";
                return Task.CompletedTask;
            });
        }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException or InvalidOperationException)
        {
            await _dispatcher.InvokeAsync(() =>
            {
                SubsystemHealthStatus = $"Subsystem health checks failed: {exception.Message}";
                return Task.CompletedTask;
            });
        }
        finally
        {
            await _dispatcher.InvokeAsync(() =>
            {
                IsSubsystemHealthRefreshing = false;
                return Task.CompletedTask;
            });
        }
    }

    [RelayCommand]
    private async Task RunDeterministicDownloadTestAsync()
    {
        if (IsDeterministicDownloadTestRunning)
        {
            return;
        }

        IsDeterministicDownloadTestRunning = true;
        DeterministicDownloadTestStatus = "Running the bounded 1 MiB configured-pipeline test…";
        try
        {
            DeterministicDownloadTestResult result = await _deterministicDownloadTestService
                .RunAsync()
                .ConfigureAwait(false);
            await _dispatcher.InvokeAsync(() =>
            {
                ApplyDeterministicDownloadTest(result);
                return Task.CompletedTask;
            });
        }
        catch (OperationCanceledException)
        {
            await _dispatcher.InvokeAsync(() =>
            {
                DeterministicDownloadTestStatus = "The bounded test download was cancelled.";
                return Task.CompletedTask;
            });
        }
        finally
        {
            await _dispatcher.InvokeAsync(() =>
            {
                IsDeterministicDownloadTestRunning = false;
                return Task.CompletedTask;
            });
        }
    }

    private async Task RepairSubsystemAsync(string repairActionId)
    {
        if (IsSubsystemHealthRefreshing)
        {
            return;
        }

        IsSubsystemHealthRefreshing = true;
        SubsystemHealthStatus = "Applying the selected bounded repair action…";
        try
        {
            SubsystemHealthSnapshot snapshot = await _subsystemHealthService
                .RepairAsync(repairActionId, GetDiagnosticsDestinationDirectory())
                .ConfigureAwait(false);
            await _dispatcher.InvokeAsync(() =>
            {
                ApplySubsystemHealth(snapshot);
                OperationMessage = "Diagnostics repair action completed and health was refreshed.";
                return Task.CompletedTask;
            });
        }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException or InvalidOperationException or ArgumentException)
        {
            await _dispatcher.InvokeAsync(() =>
            {
                SubsystemHealthStatus = $"Repair failed: {exception.Message}";
                return Task.CompletedTask;
            });
        }
        finally
        {
            await _dispatcher.InvokeAsync(() =>
            {
                IsSubsystemHealthRefreshing = false;
                return Task.CompletedTask;
            });
        }
    }

    private void OnSubsystemHealthChanged(object? sender, SubsystemHealthSnapshot snapshot)
    {
        if (_dispatcher.CheckAccess())
        {
            ApplySubsystemHealth(snapshot);
        }
        else
        {
            _dispatcher.Post(() => ApplySubsystemHealth(snapshot));
        }
    }

    private void OnDeterministicDownloadTestChanged(object? sender, EventArgs eventArgs)
    {
        DeterministicDownloadTestResult? result = _deterministicDownloadTestService.LastResult;
        if (result is null)
        {
            return;
        }

        if (_dispatcher.CheckAccess())
        {
            ApplyDeterministicDownloadTest(result);
        }
        else
        {
            _dispatcher.Post(() => ApplyDeterministicDownloadTest(result));
        }
    }

    private void ApplySubsystemHealth(SubsystemHealthSnapshot snapshot)
    {
        SubsystemHealthChecks.Clear();
        foreach (SubsystemHealthCheckResult check in snapshot.Checks)
        {
            SubsystemHealthChecks.Add(new SubsystemHealthCheckViewModel(check, RepairSubsystemAsync));
        }

        SubsystemHealthStatus = snapshot.Checks.Count == 0
            ? "Run health checks to inspect browser, native host, aria2, FFmpeg, proxy, and destination disk."
            : snapshot.ProblemCount == 0
                ? $"All {snapshot.Checks.Count} subsystem checks are healthy or intentionally disabled."
                : $"{snapshot.ProblemCount} of {snapshot.Checks.Count} subsystem checks need attention.";
    }

    private void ApplyDeterministicDownloadTest(DeterministicDownloadTestResult result)
    {
        DeterministicDownloadTestStatus = result.Summary;
        DeterministicDownloadTestDetails = string.Join(
            Environment.NewLine,
            $"Endpoint: {result.EndpointOrigin}",
            $"HTTP status: {(result.StatusCode == 0 ? "not received" : result.StatusCode.ToString(System.Globalization.CultureInfo.InvariantCulture))}",
            $"Bytes: {result.ReceivedBytes}/{result.ExpectedBytes}",
            $"Duration: {result.DurationText}",
            $"Observed rate: {result.SpeedText}",
            string.IsNullOrWhiteSpace(result.Sha256) ? "SHA-256: unavailable" : $"SHA-256: {result.Sha256}");
    }

    private void ApplySelectedTransferInsights(IReadOnlyList<TransferDiagnosticEvent> events)
    {
        TransferDiagnosticInsights insights = TransferDiagnosticInsightBuilder.Build(events);
        SelectedTransferResponseHeaders.Clear();
        foreach ((string name, string? value) in insights.ResponseHeaders.OrderBy(static item => item.Key, StringComparer.OrdinalIgnoreCase))
        {
            SelectedTransferResponseHeaders.Add(new DiagnosticKeyValueViewModel(name, value ?? string.Empty));
        }

        SelectedTransferRetryHistory.Clear();
        foreach (TransferDiagnosticEvent retry in insights.RetryHistory.Take(50))
        {
            SelectedTransferRetryHistory.Add(new TransferDiagnosticEntryViewModel(retry));
        }

        SelectedTransferSegments.Clear();
        foreach (TransferSegmentDiagnostic segment in insights.Segments)
        {
            SelectedTransferSegments.Add(segment);
        }

        SelectedTransferResumeSummary = insights.ResumeAvailabilitySummary;
    }

    private string GetDiagnosticsDestinationDirectory()
    {
        if (SelectedDownload is not null)
        {
            return Path.GetDirectoryName(SelectedDownload.DestinationPath)
                ?? _settingsService.Current.DefaultDownloadDirectory;
        }

        return string.IsNullOrWhiteSpace(DestinationFolder)
            ? _settingsService.Current.DefaultDownloadDirectory
            : DestinationFolder;
    }
}
