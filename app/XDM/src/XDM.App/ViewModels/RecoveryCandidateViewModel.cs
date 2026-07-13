using CommunityToolkit.Mvvm.ComponentModel;
using XDM.App.Services;
using XDM.Core.Downloads;
using XDM.Core.Localization;

namespace XDM.App.ViewModels;

public sealed partial class RecoveryCandidateViewModel : ObservableObject
{
    private readonly LocalizationService _localization;

    public RecoveryCandidateViewModel(
        DownloadRecoveryCandidate candidate,
        LocalizationService localization)
    {
        _localization = localization;
        Apply(candidate);
    }

    public DownloadRecoveryCandidate Candidate { get; private set; } = null!;

    public string Id => Candidate.Id;

    public string? DownloadId => Candidate.DownloadId;

    public string FileName => Candidate.FileName;

    public Uri? Source => Candidate.Source;

    public string SourceText => Candidate.Source?.AbsoluteUri ?? "No source metadata";

    public string DestinationPath => Candidate.DestinationPath;

    public string PartialPath => Candidate.PartialPath;

    public string PartialBytesText => LocaleFormatter.FormatBytes(Candidate.PartialBytes, _localization.Culture);

    public string ExpectedTotalText => Candidate.ExpectedTotalBytes is long total
        ? LocaleFormatter.FormatBytes(total, _localization.Culture)
        : "Unknown";

    public string CheckpointText => Candidate.LastCheckpointAt?.ToLocalTime().ToString("g", _localization.Culture)
        ?? "No checkpoint timestamp";

    public string ClassificationText => Candidate.Classification switch
    {
        DownloadRecoveryClassification.ReadyToResume => "Ready to resume",
        DownloadRecoveryClassification.NeedsRemoteValidation => "Needs remote validation",
        DownloadRecoveryClassification.NeedsRepair => "Needs repair",
        DownloadRecoveryClassification.MissingPartialFile => "Missing partial file",
        DownloadRecoveryClassification.RemoteFileChanged => "Remote file changed",
        DownloadRecoveryClassification.AlreadyCompleteNotFinalized => "Completion recovered",
        DownloadRecoveryClassification.OrphanedArtifact => "Orphaned artifact",
        _ => Candidate.Classification.ToString()
    };

    public string ValidatorText => Candidate.ResumeValidatorStatus;

    public string EntityTagText => Candidate.EntityTag ?? "—";

    public string LastModifiedText => Candidate.LastModified?.ToLocalTime().ToString("g", _localization.Culture) ?? "—";

    public string ChecksumText => Candidate.HasExpectedChecksum
        ? $"{Candidate.ExpectedChecksumAlgorithm ?? "checksum"}: {Candidate.ExpectedChecksum}"
        : "No expected checksum";

    public string RecommendedAction => Candidate.RecommendedAction;

    public string UnsafeReason => string.IsNullOrWhiteSpace(Candidate.UnsafeReason)
        ? "The persisted validators are sufficient for a guarded resume."
        : Candidate.UnsafeReason;

    public bool CanResume => Candidate.CanResume;

    public bool CanValidate => Candidate.CanValidate;

    public bool CanRepair => Candidate.CanRepair;

    public bool IsOrphaned => Candidate.IsOrphaned;

    public void Apply(DownloadRecoveryCandidate candidate)
    {
        Candidate = candidate;
        OnPropertyChanged(string.Empty);
    }
}
