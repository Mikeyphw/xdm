using System.Collections.ObjectModel;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using XDM.Core.Downloads;
using XDM.DownloadEngine;

namespace XDM.App.ViewModels;

public partial class MainWindowViewModel
{
    public ObservableCollection<RecoveryCandidateViewModel> RecoveryCandidates { get; } = [];

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(HasSelectedRecoveryCandidate))]
    private RecoveryCandidateViewModel? selectedRecoveryCandidate;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(CanUseRecoveryActions))]
    private bool isRecoveryBusy;

    public bool HasRecoveryCandidates => RecoveryCandidates.Count > 0;

    public bool HasSelectedRecoveryCandidate => SelectedRecoveryCandidate is not null;

    public bool CanUseRecoveryActions => !IsRecoveryBusy && HasSelectedRecoveryCandidate;

    public string RecoveryCandidateSummary => RecoveryCandidates.Count switch
    {
        0 => "No interrupted transfers or orphaned artifacts require review.",
        1 => "1 recovery item is waiting for review.",
        _ => $"{RecoveryCandidates.Count} recovery items are waiting for review."
    };

    [RelayCommand]
    private async Task RefreshRecoveryAsync()
    {
        await RunRecoveryActionAsync(
            () => _downloadRecoveryCoordinator.ScanAsync(_recoveryService.PreviousSessionWasUnclean),
            "Recovery scan completed.");
    }

    [RelayCommand]
    private async Task ValidateRecoveryAsync()
    {
        RecoveryCandidateViewModel? selected = SelectedRecoveryCandidate;
        if (selected is null || !selected.CanValidate)
        {
            return;
        }

        await RunRecoveryActionAsync(
            async () =>
            {
                DownloadRecoveryCandidate updated = await _downloadRecoveryCoordinator
                    .ValidateAsync(selected.Id);
                OperationMessage = updated.Classification == DownloadRecoveryClassification.RemoteFileChanged
                    ? "Validation found that the remote file changed. Resume remains blocked."
                    : "Remote identity validation completed.";
            },
            null);
    }

    [RelayCommand]
    private async Task ResumeRecoveryAsync()
    {
        RecoveryCandidateViewModel? selected = SelectedRecoveryCandidate;
        if (selected?.DownloadId is not string downloadId || !selected.CanResume)
        {
            return;
        }

        await RunRecoveryActionAsync(
            async () =>
            {
                await _downloadManager.ResumeAsync(downloadId);
                _downloadRecoveryCoordinator.Dismiss(selected.Id);
            },
            "The recovered transfer was resumed with server validation enabled.");
    }

    [RelayCommand]
    private async Task VerifyAndRepairRecoveryAsync()
    {
        RecoveryCandidateViewModel? selected = SelectedRecoveryCandidate;
        if (selected?.DownloadId is not string downloadId || !selected.CanRepair)
        {
            return;
        }

        await RunRecoveryActionAsync(
            async () =>
            {
                DownloadRepairResult result = await _downloadManager.RepairAsync(downloadId);
                _downloadRecoveryCoordinator.Dismiss(selected.Id);
                OperationMessage = result.Message;
            },
            null);
    }

    [RelayCommand]
    private async Task RestartRecoveryFromZeroAsync()
    {
        RecoveryCandidateViewModel? selected = SelectedRecoveryCandidate;
        if (selected?.DownloadId is not string downloadId)
        {
            return;
        }

        await RunRecoveryActionAsync(
            async () =>
            {
                DownloadRepairResult result = await _downloadManager.RestartFromZeroAsync(downloadId);
                _downloadRecoveryCoordinator.Dismiss(selected.Id);
                OperationMessage = $"Restarted from zero. {result.Message}";
            },
            null);
    }

    [RelayCommand]
    private async Task LocateRecoveryPartialAsync()
    {
        RecoveryCandidateViewModel? selected = SelectedRecoveryCandidate;
        if (selected is null)
        {
            return;
        }

        string target = File.Exists(selected.PartialPath)
            ? selected.PartialPath
            : selected.DestinationPath;
        await RunRecoveryActionAsync(
            () => _platformService.RevealFileAsync(target),
            "Opened the partial-file location.");
    }

    [RelayCommand]
    private async Task OpenRecoveryFolderAsync()
    {
        RecoveryCandidateViewModel? selected = SelectedRecoveryCandidate;
        if (selected is null)
        {
            return;
        }

        string? directory = Path.GetDirectoryName(selected.DestinationPath);
        if (string.IsNullOrWhiteSpace(directory))
        {
            return;
        }
        await RunRecoveryActionAsync(
            () => _platformService.RevealFileAsync(directory),
            "Opened the recovery artifact folder.");
    }

    [RelayCommand]
    private async Task RemoveRecoveryRecordAsync()
    {
        RecoveryCandidateViewModel? selected = SelectedRecoveryCandidate;
        if (selected is null)
        {
            return;
        }

        await RunRecoveryActionAsync(
            async () =>
            {
                if (selected.DownloadId is string downloadId)
                {
                    await _downloadManager.RemoveAsync(downloadId, deletePartialFile: false);
                }
                _downloadRecoveryCoordinator.Dismiss(selected.Id);
            },
            "Removed the recovery record without deleting local artifacts.");
    }

    private async Task RunRecoveryActionAsync(Func<Task> action, string? successMessage)
    {
        if (IsRecoveryBusy)
        {
            return;
        }

        IsRecoveryBusy = true;
        try
        {
            await action();
            if (!string.IsNullOrWhiteSpace(successMessage))
            {
                OperationMessage = successMessage;
            }
        }
        catch (Exception exception) when (exception is IOException
            or UnauthorizedAccessException
            or HttpRequestException
            or InvalidOperationException
            or KeyNotFoundException)
        {
            OperationMessage = $"Recovery action failed: {exception.Message}";
        }
        finally
        {
            IsRecoveryBusy = false;
        }
    }

    private void OnRecoveryCandidatesChanged(object? sender, EventArgs eventArgs)
    {
        IReadOnlyList<DownloadRecoveryCandidate> candidates = _downloadRecoveryCoordinator.Current;
        if (_dispatcher.CheckAccess())
        {
            ApplyRecoveryCandidates(candidates);
        }
        else
        {
            _dispatcher.Post(() => ApplyRecoveryCandidates(candidates));
        }
    }

    private void ApplyRecoveryCandidates(IReadOnlyList<DownloadRecoveryCandidate> candidates)
    {
        string? selectedId = SelectedRecoveryCandidate?.Id;
        Dictionary<string, RecoveryCandidateViewModel> existing = RecoveryCandidates
            .ToDictionary(static item => item.Id, StringComparer.Ordinal);
        RecoveryCandidates.Clear();
        foreach (DownloadRecoveryCandidate candidate in candidates)
        {
            if (!existing.TryGetValue(candidate.Id, out RecoveryCandidateViewModel? viewModel))
            {
                viewModel = new RecoveryCandidateViewModel(candidate, _localization);
            }
            else
            {
                viewModel.Apply(candidate);
            }
            RecoveryCandidates.Add(viewModel);
        }

        SelectedRecoveryCandidate = RecoveryCandidates.FirstOrDefault(item =>
                string.Equals(item.Id, selectedId, StringComparison.Ordinal))
            ?? RecoveryCandidates.FirstOrDefault();
        OnPropertyChanged(nameof(HasRecoveryCandidates));
        OnPropertyChanged(nameof(RecoveryCandidateSummary));
        OnPropertyChanged(nameof(ShowRecoveryReview));
        OnPropertyChanged(nameof(RecoveryReviewSummary));
    }
}
