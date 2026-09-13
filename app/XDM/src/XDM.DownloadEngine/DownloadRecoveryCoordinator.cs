using System.Net;
using System.Net.Http.Headers;
using System.Text.Json;
using XDM.Core.Downloads;
using XDM.Core.Persistence;
using XDM.Core.Settings;
using XDM.Core.State;

namespace XDM.DownloadEngine;

public sealed class DownloadRecoveryCoordinator : IDownloadRecoveryCoordinator, IDisposable
{
    private const int MaximumScannedArtifacts = 4096;
    private static readonly TimeSpan ValidationTimeout = TimeSpan.FromSeconds(15);
    private static readonly string[] ArtifactSuffixes =
    [
        ".xdm.part",
        ".xdm.resume.json",
        ".xdm.finalizing",
        ".xdm.promoting",
        ".xdm.checksums.json",
        ".xdm.recovery-dismissed.json"
    ];
    private const string SegmentDirectorySuffix = ".segments";
    private static readonly EnumerationOptions ArtifactEnumerationOptions = new()
    {
        RecurseSubdirectories = true,
        IgnoreInaccessible = true,
        AttributesToSkip = FileAttributes.ReparsePoint
    };
    private readonly IApplicationState _applicationState;
    private readonly IDownloadHistoryStore _historyStore;
    private readonly ISettingsService _settingsService;
    private readonly HttpClient _httpClient;
    private readonly ResumeCheckpointStore _checkpointStore = new();
    private readonly FinalizationJournalStore _finalizationJournalStore = new();
    private readonly RecoveryDismissalStore _dismissalStore = new();
    private readonly object _sync = new();
    private DownloadRecoveryCandidate[] _current = [];

    public DownloadRecoveryCoordinator(
        IApplicationState applicationState,
        IDownloadHistoryStore historyStore,
        ISettingsService settingsService,
        HttpClient httpClient)
    {
        ArgumentNullException.ThrowIfNull(applicationState);
        ArgumentNullException.ThrowIfNull(historyStore);
        ArgumentNullException.ThrowIfNull(settingsService);
        ArgumentNullException.ThrowIfNull(httpClient);
        _applicationState = applicationState;
        _historyStore = historyStore;
        _settingsService = settingsService;
        _httpClient = httpClient;
        _applicationState.Changed += OnApplicationStateChanged;
    }

    public event EventHandler? Changed;

    public IReadOnlyList<DownloadRecoveryCandidate> Current
    {
        get
        {
            lock (_sync)
            {
                return _current;
            }
        }
    }

    public async Task ScanAsync(
        bool previousSessionWasUnclean,
        IReadOnlyCollection<string>? previousActiveDownloadIds = null,
        bool? checkpointFlushSucceeded = null,
        CancellationToken cancellationToken = default)
    {
        IReadOnlyList<PersistedDownload> persisted = await _historyStore
            .LoadAsync(cancellationToken)
            .ConfigureAwait(false);
        Dictionary<string, DownloadSnapshot> snapshots = _applicationState.Current.Downloads
            .ToDictionary(static item => item.Id, StringComparer.Ordinal);
        List<DownloadRecoveryCandidate> candidates = [];
        HashSet<string> knownDestinations = new(GetPathComparer());
        HashSet<string> previouslyActive = !previousSessionWasUnclean || previousActiveDownloadIds is null
            ? new HashSet<string>(StringComparer.Ordinal)
            : new HashSet<string>(previousActiveDownloadIds, StringComparer.Ordinal);

        foreach (PersistedDownload item in persisted)
        {
            cancellationToken.ThrowIfCancellationRequested();
            string destinationPath = Path.GetFullPath(item.DestinationPath);
            knownDestinations.Add(destinationPath);
            if (await _dismissalStore.IsDismissedAsync(destinationPath, cancellationToken).ConfigureAwait(false))
            {
                continue;
            }
            snapshots.TryGetValue(item.Id, out DownloadSnapshot? snapshot);
            DownloadRecoveryCandidate? candidate = await AssessKnownAsync(
                item,
                snapshot,
                previousSessionWasUnclean,
                previouslyActive.Contains(item.Id),
                checkpointFlushSucceeded,
                cancellationToken).ConfigureAwait(false);
            if (candidate is not null)
            {
                candidates.Add(candidate);
            }
        }

        foreach (string destinationPath in EnumerateArtifactDestinations(GetScanRoots(persisted), cancellationToken))
        {
            if (knownDestinations.Contains(Path.GetFullPath(destinationPath))
                || await _dismissalStore.IsDismissedAsync(destinationPath, cancellationToken).ConfigureAwait(false))
            {
                continue;
            }

            DownloadRecoveryCandidate? orphan = await AssessOrphanAsync(destinationPath, cancellationToken)
                .ConfigureAwait(false);
            if (orphan is not null)
            {
                candidates.Add(orphan);
            }
        }

        Publish(candidates
            .DistinctBy(static candidate => candidate.Id, StringComparer.Ordinal)
            .OrderBy(static candidate => candidate.Classification)
            .ThenByDescending(static candidate => candidate.LastCheckpointAt)
            .ThenBy(static candidate => candidate.FileName, StringComparer.OrdinalIgnoreCase)
            .ToArray());
    }

    public async Task<DownloadRecoveryCandidate> ValidateAsync(
        string candidateId,
        CancellationToken cancellationToken = default)
    {
        DownloadRecoveryCandidate candidate = Current.FirstOrDefault(item =>
                string.Equals(item.Id, candidateId, StringComparison.Ordinal))
            ?? throw new KeyNotFoundException($"Recovery candidate '{candidateId}' was not found.");
        if (!candidate.CanValidate || candidate.Source is null)
        {
            return candidate;
        }

        using CancellationTokenSource timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(ValidationTimeout);
        RemoteIdentity identity;
        try
        {
            identity = await ProbeRemoteIdentityAsync(candidate.Source, timeout.Token).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            return Replace(candidate with
            {
                Classification = DownloadRecoveryClassification.NeedsRemoteValidation,
                RecommendedAction = "Retry validation when the network is available.",
                UnsafeReason = "Remote validation timed out before XDM could prove that the partial belongs to the current file.",
                ResumeValidatorStatus = "Validation timed out"
            });
        }
        catch (HttpRequestException exception)
        {
            return Replace(candidate with
            {
                Classification = DownloadRecoveryClassification.NeedsRemoteValidation,
                RecommendedAction = "Retry validation when the network is available.",
                UnsafeReason = $"Remote identity could not be checked: {exception.Message}",
                ResumeValidatorStatus = "Remote unavailable"
            });
        }

        RemoteValidationResult validation = EvaluateRemoteIdentity(candidate, identity);
        if (validation.Changed)
        {
            return Replace(candidate with
            {
                Classification = DownloadRecoveryClassification.RemoteFileChanged,
                RecommendedAction = "Restart from zero or preserve the partial file before downloading the changed remote object.",
                UnsafeReason = validation.Message,
                ResumeValidatorStatus = "Remote file changed",
                RemoteIdentityValidated = false,
                RepairSupported = false
            });
        }

        if (!validation.Proven)
        {
            return Replace(candidate with
            {
                Classification = DownloadRecoveryClassification.NeedsRemoteValidation,
                RecommendedAction = "Remote identity is still unproven. Retry validation or restart from zero.",
                UnsafeReason = validation.Message,
                ResumeValidatorStatus = "Remote identity unproven",
                RemoteIdentityValidated = false
            });
        }

        bool requiresRanges = candidate.PartialBytes > 0;
        if (requiresRanges && !identity.AcceptsRanges)
        {
            return Replace(candidate with
            {
                Classification = DownloadRecoveryClassification.NeedsRepair,
                RecommendedAction = "The server identity matched, but byte-range resume is unavailable. Restart from zero.",
                UnsafeReason = "The server did not advertise byte-range support, so existing partial bytes cannot be safely appended or selectively repaired.",
                ResumeValidatorStatus = "Validated; byte ranges unavailable",
                RemoteIdentityValidated = false,
                RepairSupported = false
            });
        }

        return Replace(candidate with
        {
            Classification = DownloadRecoveryClassification.ReadyToResume,
            RecommendedAction = "Resume the download. XDM proved the remote identity and will revalidate the range response before appending.",
            UnsafeReason = string.Empty,
            ResumeValidatorStatus = requiresRanges
                ? "Validated; byte ranges supported"
                : "Validated; no partial bytes require a range",
            RemoteIdentityValidated = true
        });
    }

    public async Task DismissAsync(
        string candidateId,
        bool persist = false,
        CancellationToken cancellationToken = default)
    {
        DownloadRecoveryCandidate? candidate = Current.FirstOrDefault(item =>
            string.Equals(item.Id, candidateId, StringComparison.Ordinal));
        if (candidate is null)
        {
            return;
        }

        if (persist)
        {
            await _dismissalStore
                .SaveAsync(candidate.DestinationPath, candidate.Id, cancellationToken)
                .ConfigureAwait(false);
        }

        RemoveCandidate(candidateId);
    }

    public void MarkResumeStarted(string candidateId)
    {
        DownloadRecoveryCandidate? candidate = Current.FirstOrDefault(item =>
            string.Equals(item.Id, candidateId, StringComparison.Ordinal));
        if (candidate is null)
        {
            return;
        }

        Replace(candidate with
        {
            Classification = DownloadRecoveryClassification.ResumeInProgress,
            RecommendedAction = "The transfer is running. This recovery record will clear only after the download completes safely.",
            UnsafeReason = string.Empty,
            ResumeValidatorStatus = "Validated; resume in progress",
            OperationInProgress = true,
            RemoteIdentityValidated = true
        });
    }

    private async Task<DownloadRecoveryCandidate?> AssessKnownAsync(
        PersistedDownload persisted,
        DownloadSnapshot? snapshot,
        bool previousSessionWasUnclean,
        bool wasTrackedActive,
        bool? checkpointFlushSucceeded,
        CancellationToken cancellationToken)
    {
        string destination = Path.GetFullPath(persisted.DestinationPath);
        string xdmPartialPath = TransferArtifactPaths.GetPartialPath(destination);
        string checkpointPath = TransferArtifactPaths.GetCheckpointPath(destination);
        string finalizationPath = TransferArtifactPaths.GetFinalizationMarkerPath(destination);
        bool aria2DestinationOwnsProgress = persisted.Backend == DownloadBackendKind.Aria2 && File.Exists(destination);
        string partialPath = aria2DestinationOwnsProgress ? destination : xdmPartialPath;
        long partialBytes = GetArtifactBytes(destination);
        if (aria2DestinationOwnsProgress)
        {
            partialBytes = Math.Max(partialBytes, new FileInfo(destination).Length);
        }
        ResumeCheckpoint? checkpoint = await _checkpointStore.LoadAsync(destination, cancellationToken)
            .ConfigureAwait(false);
        FinalizationMarker? finalization = await ReadFinalizationMarkerAsync(destination, cancellationToken)
            .ConfigureAwait(false);
        bool wasActive = wasTrackedActive || persisted.State is DownloadState.Connecting
            or DownloadState.Downloading
            or DownloadState.Finalizing;
        bool hasArtifacts = partialBytes > 0
            || File.Exists(checkpointPath)
            || File.Exists(finalizationPath)
            || File.Exists(TransferArtifactPaths.GetFinalizationStagingPath(destination))
            || Directory.Exists(SegmentedDownloadExecutor.GetSegmentDirectory(destination));
        bool recoveredFinalization = snapshot?.State == DownloadState.Completed
            && snapshot.RecoveryMessage?.Contains("interrupted during finalization", StringComparison.OrdinalIgnoreCase) == true;
        if (!wasActive
            && !hasArtifacts
            && snapshot?.RecoveryRequired != true
            && !recoveredFinalization
            && !(previousSessionWasUnclean && persisted.State == DownloadState.Paused && persisted.DownloadedBytes > 0)
            && !(previousSessionWasUnclean && wasTrackedActive))
        {
            return null;
        }

        DownloadRecoveryClassification classification;
        string action;
        string reason;
        bool finalizationLooksComplete = finalization is not null
            && await HasValidFinalizationCandidateAsync(destination, finalization, cancellationToken)
                .ConfigureAwait(false);
        if (recoveredFinalization || finalizationLooksComplete)
        {
            classification = DownloadRecoveryClassification.AlreadyCompleteNotFinalized;
            action = snapshot?.State == DownloadState.Completed
                ? "Review the recovered completed file, then dismiss this recovery record."
                : "Validate the completed partial and finish finalization before resuming other work.";
            reason = snapshot?.State == DownloadState.Completed
                ? "XDM recovered a crash window between final rename and persisted completion state."
                : "A durable finalization marker shows that completion was interrupted after all expected bytes were written.";
        }
        else if (snapshot?.RecoveryMessage?.Contains("remote file changed", StringComparison.OrdinalIgnoreCase) == true)
        {
            classification = DownloadRecoveryClassification.RemoteFileChanged;
            action = "Restart from zero or preserve the stale partial data.";
            reason = snapshot.RecoveryMessage;
        }
        else if ((checkpoint?.DownloadedBytes ?? persisted.DownloadedBytes) > 0 && partialBytes == 0)
        {
            classification = DownloadRecoveryClassification.MissingPartialFile;
            action = "Locate the missing partial file or restart the download from zero.";
            reason = "Persisted progress exists, but no partial or segment bytes were found.";
        }
        else if (snapshot?.RecoveryRequired == true
            || (persisted.TotalBytes is long total && partialBytes > total)
            || (checkpoint?.TotalBytes is long checkpointTotal && partialBytes > checkpointTotal))
        {
            classification = DownloadRecoveryClassification.NeedsRepair;
            action = "Review the local artifacts, then use Verify and repair or restart from zero.";
            reason = snapshot?.RecoveryMessage
                ?? "The local artifact lengths or checkpoint ownership are inconsistent.";
        }
        else if (wasTrackedActive && checkpointFlushSucceeded == false)
        {
            classification = DownloadRecoveryClassification.NeedsRemoteValidation;
            action = "Validate the remote identity and local artifact length before resuming.";
            reason = "The previous shutdown did not complete its checkpoint flush for this active transfer.";
        }
        else
        {
            classification = DownloadRecoveryClassification.NeedsRemoteValidation;
            action = "Validate the remote identity before resuming.";
            bool hasPersistedValidator = HasUsableValidator(
                checkpoint?.EntityTag ?? persisted.EntityTag,
                checkpoint?.LastModified ?? persisted.LastModified);
            reason = partialBytes > 0
                ? hasPersistedValidator
                    ? "A persisted validator exists, but it has not yet been compared with the current remote object in this recovery session."
                    : "The partial file has no strong ETag or Last-Modified validator that can prove remote identity."
                : "The previous session ended while this transfer was active; XDM must validate the remote object before re-admitting it.";
        }

        string? entityTag = checkpoint?.EntityTag ?? persisted.EntityTag;
        DateTimeOffset? lastModified = checkpoint?.LastModified ?? persisted.LastModified;
        return new DownloadRecoveryCandidate(
            persisted.Id,
            persisted.Id,
            Path.GetFileName(destination),
            persisted.Source,
            destination,
            partialPath,
            partialBytes,
            checkpoint?.TotalBytes ?? persisted.TotalBytes,
            checkpoint?.UpdatedAt ?? persisted.UpdatedAt,
            FormatValidatorStatus(entityTag, lastModified),
            entityTag,
            lastModified,
            checkpoint?.ExpectedChecksumAlgorithm ?? persisted.ExpectedChecksumAlgorithm,
            checkpoint?.ExpectedChecksum ?? persisted.ExpectedChecksum,
            classification,
            action,
            reason,
            RepairSupported: SupportsSelectiveRepair(
                persisted,
                checkpoint?.TotalBytes ?? persisted.TotalBytes,
                classification,
                partialBytes));
    }

    private async Task<DownloadRecoveryCandidate?> AssessOrphanAsync(
        string destination,
        CancellationToken cancellationToken)
    {
        string partialPath = TransferArtifactPaths.GetPartialPath(destination);
        string checkpointPath = TransferArtifactPaths.GetCheckpointPath(destination);
        string finalizationPath = TransferArtifactPaths.GetFinalizationMarkerPath(destination);
        long bytes = GetArtifactBytes(destination);
        bool hasArtifact = bytes > 0
            || File.Exists(checkpointPath)
            || File.Exists(finalizationPath)
            || File.Exists(TransferArtifactPaths.GetFinalizationStagingPath(destination))
            || Directory.Exists(SegmentedDownloadExecutor.GetSegmentDirectory(destination));
        if (!hasArtifact)
        {
            return null;
        }

        ResumeCheckpoint? checkpoint = await _checkpointStore.LoadAsync(destination, cancellationToken)
            .ConfigureAwait(false);
        FinalizationMarker? finalization = await ReadFinalizationMarkerAsync(destination, cancellationToken)
            .ConfigureAwait(false);
        string stableId = $"orphan:{Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(destination))).ToLowerInvariant()}";
        return new DownloadRecoveryCandidate(
            stableId,
            null,
            Path.GetFileName(destination),
            checkpoint?.Source,
            destination,
            partialPath,
            bytes,
            checkpoint?.TotalBytes ?? finalization?.ExpectedLength,
            checkpoint?.UpdatedAt ?? finalization?.CreatedAt,
            FormatValidatorStatus(checkpoint?.EntityTag, checkpoint?.LastModified),
            checkpoint?.EntityTag,
            checkpoint?.LastModified,
            checkpoint?.ExpectedChecksumAlgorithm ?? finalization?.ChecksumAlgorithm,
            checkpoint?.ExpectedChecksum ?? finalization?.Checksum,
            DownloadRecoveryClassification.OrphanedArtifact,
            "Inspect or relocate the artifacts, then remove this recovery record when no longer needed.",
            "Transfer artifacts were found without a matching download-history record.",
            IsOrphaned: true);
    }

    private string[] GetScanRoots(IReadOnlyList<PersistedDownload> persisted)
    {
        HashSet<string> roots = new(GetPathComparer());
        AddRoot(roots, _settingsService.Current.DefaultDownloadDirectory);
        foreach (DownloadCategoryDefinition category in _settingsService.Current.Categories)
        {
            AddRoot(roots, category.DestinationDirectory);
        }
        foreach (DestinationRuleDefinition rule in (_settingsService.Current.Organization ?? OrganizationSettings.Default).DestinationRules)
        {
            AddRoot(roots, rule.DestinationDirectory);
        }
        foreach (PersistedDownload item in persisted)
        {
            AddRoot(roots, Path.GetDirectoryName(item.DestinationPath));
        }
        return roots.ToArray();
    }

    private static IEnumerable<string> EnumerateArtifactDestinations(
        string[] roots,
        CancellationToken cancellationToken)
    {
        HashSet<string> destinations = new(GetPathComparer());
        int count = 0;
        foreach (string root in roots)
        {
            if (!Directory.Exists(root))
            {
                continue;
            }
            string[] files;
            try
            {
                files = Directory
                    .EnumerateFiles(root, "*.xdm.*", ArtifactEnumerationOptions)
                    .Take(MaximumScannedArtifacts - count)
                    .ToArray();
            }
            catch (IOException)
            {
                continue;
            }
            catch (UnauthorizedAccessException)
            {
                continue;
            }

            foreach (string path in files)
            {
                cancellationToken.ThrowIfCancellationRequested();
                string? destination = TryGetDestinationFromArtifact(path);
                if (destination is not null && destinations.Add(destination))
                {
                    yield return destination;
                }
                count++;
                if (count >= MaximumScannedArtifacts)
                {
                    yield break;
                }
            }

            string[] segmentDirectories;
            try
            {
                segmentDirectories = Directory
                    .EnumerateDirectories(root, $"*{SegmentDirectorySuffix}", ArtifactEnumerationOptions)
                    .Take(MaximumScannedArtifacts - count)
                    .ToArray();
            }
            catch (IOException)
            {
                continue;
            }
            catch (UnauthorizedAccessException)
            {
                continue;
            }

            foreach (string segmentDirectory in segmentDirectories)
            {
                cancellationToken.ThrowIfCancellationRequested();
                string fullPath = Path.GetFullPath(segmentDirectory);
                if (fullPath.EndsWith(SegmentDirectorySuffix, StringComparison.OrdinalIgnoreCase))
                {
                    string destination = fullPath[..^SegmentDirectorySuffix.Length];
                    if (destinations.Add(destination))
                    {
                        yield return destination;
                    }
                }
                count++;
                if (count >= MaximumScannedArtifacts)
                {
                    yield break;
                }
            }
        }
    }

    private async Task<RemoteIdentity> ProbeRemoteIdentityAsync(Uri source, CancellationToken cancellationToken)
    {
        using HttpRequestMessage head = new(HttpMethod.Head, source);
        using HttpResponseMessage headResponse = await _httpClient
            .SendAsync(head, HttpCompletionOption.ResponseHeadersRead, cancellationToken)
            .ConfigureAwait(false);
        if (headResponse.StatusCode is HttpStatusCode.MethodNotAllowed or HttpStatusCode.NotImplemented)
        {
            using HttpRequestMessage range = new(HttpMethod.Get, source);
            range.Headers.Range = new RangeHeaderValue(0, 0);
            using HttpResponseMessage rangeResponse = await _httpClient
                .SendAsync(range, HttpCompletionOption.ResponseHeadersRead, cancellationToken)
                .ConfigureAwait(false);
            rangeResponse.EnsureSuccessStatusCode();
            long? length = rangeResponse.Content.Headers.ContentRange?.Length
                ?? rangeResponse.Content.Headers.ContentLength;
            return CreateIdentity(rangeResponse, length);
        }

        headResponse.EnsureSuccessStatusCode();
        return CreateIdentity(headResponse, headResponse.Content.Headers.ContentLength);
    }

    private static RemoteIdentity CreateIdentity(HttpResponseMessage response, long? length)
        => new(
            response.Headers.ETag?.ToString(),
            response.Content.Headers.LastModified,
            length,
            response.StatusCode == HttpStatusCode.PartialContent
                || response.Headers.AcceptRanges.Any(static value =>
                    string.Equals(value, "bytes", StringComparison.OrdinalIgnoreCase)));

    private static RemoteValidationResult EvaluateRemoteIdentity(
        DownloadRecoveryCandidate candidate,
        RemoteIdentity remote)
    {
        if (candidate.ExpectedTotalBytes is long expectedLength)
        {
            if (remote.Length is not long actualLength)
            {
                return new(false, false, "The server did not expose a content length, so the persisted object length cannot be proven.");
            }
            if (expectedLength != actualLength)
            {
                return new(false, true, $"The remote length changed from {expectedLength} to {actualLength} bytes.");
            }
        }

        bool hasStrongEtag = !string.IsNullOrWhiteSpace(candidate.EntityTag)
            && !candidate.EntityTag.StartsWith("W/", StringComparison.OrdinalIgnoreCase);
        if (hasStrongEtag)
        {
            if (string.IsNullOrWhiteSpace(remote.EntityTag))
            {
                return new(false, false, "The persisted strong ETag is no longer exposed by the server.");
            }
            if (!string.Equals(candidate.EntityTag, remote.EntityTag, StringComparison.Ordinal))
            {
                return new(false, true, "The remote strong ETag no longer matches the persisted checkpoint.");
            }
            return new(true, false, "The remote strong ETag and known length match the persisted checkpoint.");
        }

        if (candidate.LastModified is DateTimeOffset expectedModified)
        {
            if (remote.LastModified is not DateTimeOffset actualModified)
            {
                return new(false, false, "The persisted Last-Modified validator is no longer exposed by the server.");
            }
            if (expectedModified != actualModified)
            {
                return new(false, true, "The remote Last-Modified validator no longer matches the persisted checkpoint.");
            }
            return new(true, false, "The remote Last-Modified validator and known length match the persisted checkpoint.");
        }

        return new(false, false, "No strong persisted remote validator is available to prove that the partial belongs to the current remote object.");
    }

    private DownloadRecoveryCandidate Replace(DownloadRecoveryCandidate updated)
    {
        DownloadRecoveryCandidate[] next;
        lock (_sync)
        {
            next = _current
                .Select(candidate => string.Equals(candidate.Id, updated.Id, StringComparison.Ordinal)
                    ? updated
                    : candidate)
                .ToArray();
            _current = next;
        }
        Changed?.Invoke(this, EventArgs.Empty);
        return updated;
    }

    private void RemoveCandidate(string candidateId)
    {
        bool changed;
        lock (_sync)
        {
            DownloadRecoveryCandidate[] next = _current
                .Where(candidate => !string.Equals(candidate.Id, candidateId, StringComparison.Ordinal))
                .ToArray();
            changed = next.Length != _current.Length;
            _current = next;
        }
        if (changed)
        {
            Changed?.Invoke(this, EventArgs.Empty);
        }
    }

    private void OnApplicationStateChanged(object? sender, ApplicationSnapshot snapshot)
    {
        DownloadRecoveryCandidate[] tracked;
        lock (_sync)
        {
            tracked = _current.Where(static candidate => candidate.OperationInProgress).ToArray();
        }
        if (tracked.Length == 0)
        {
            return;
        }

        Dictionary<string, DownloadSnapshot> downloads = snapshot.Downloads
            .ToDictionary(static item => item.Id, StringComparer.Ordinal);
        foreach (DownloadRecoveryCandidate candidate in tracked)
        {
            if (candidate.DownloadId is not string downloadId
                || !downloads.TryGetValue(downloadId, out DownloadSnapshot? download))
            {
                continue;
            }

            if (download.State == DownloadState.Completed && !download.RecoveryRequired)
            {
                RemoveCandidate(candidate.Id);
                continue;
            }

            if (download.State is DownloadState.Failed or DownloadState.Paused)
            {
                Replace(candidate with
                {
                    Classification = download.RecoveryRequired
                        ? DownloadRecoveryClassification.NeedsRepair
                        : DownloadRecoveryClassification.NeedsRemoteValidation,
                    RecommendedAction = download.RecoveryRequired
                        ? "The resumed transfer stopped with a recovery condition. Review and repair or restart it."
                        : "The resumed transfer stopped before completion. Revalidate the remote identity before resuming again.",
                    UnsafeReason = download.RecoveryMessage
                        ?? "The recovery resume did not complete, so its previous validation proof is no longer sufficient for another append.",
                    ResumeValidatorStatus = "Resume stopped before completion",
                    OperationInProgress = false,
                    RemoteIdentityValidated = false,
                    RepairSupported = candidate.RepairSupported && download.RecoveryRequired
                });
            }
        }
    }

    private static bool SupportsSelectiveRepair(
        PersistedDownload persisted,
        long? expectedLength,
        DownloadRecoveryClassification classification,
        long localArtifactBytes)
        => classification == DownloadRecoveryClassification.NeedsRepair
            && localArtifactBytes > 0
            && expectedLength is > 0
            && string.Equals(persisted.Method, "GET", StringComparison.OrdinalIgnoreCase)
            && persisted.Source.Scheme is "http" or "https";

    public void Dispose()
        => _applicationState.Changed -= OnApplicationStateChanged;

    private void Publish(DownloadRecoveryCandidate[] candidates)
    {
        lock (_sync)
        {
            _current = candidates;
        }
        Changed?.Invoke(this, EventArgs.Empty);
    }

    private static long GetArtifactBytes(string destination)
    {
        string partial = TransferArtifactPaths.GetPartialPath(destination);
        long bytes = File.Exists(partial) ? new FileInfo(partial).Length : 0;
        string staging = TransferArtifactPaths.GetFinalizationStagingPath(destination);
        if (File.Exists(staging))
        {
            bytes = Math.Max(bytes, new FileInfo(staging).Length);
        }
        string segmentDirectory = SegmentedDownloadExecutor.GetSegmentDirectory(destination);
        if (!Directory.Exists(segmentDirectory))
        {
            return bytes;
        }
        try
        {
            long segmentBytes = 0;
            foreach (string path in Directory.EnumerateFiles(segmentDirectory, "*.part", SearchOption.TopDirectoryOnly))
            {
                segmentBytes = checked(segmentBytes + new FileInfo(path).Length);
            }
            return Math.Max(bytes, segmentBytes);
        }
        catch (IOException)
        {
            return bytes;
        }
        catch (UnauthorizedAccessException)
        {
            return bytes;
        }
        catch (OverflowException)
        {
            return long.MaxValue;
        }
    }

    private async Task<FinalizationMarker?> ReadFinalizationMarkerAsync(
        string destinationPath,
        CancellationToken cancellationToken)
    {
        try
        {
            return await _finalizationJournalStore
                .LoadAsync(destinationPath, cancellationToken)
                .ConfigureAwait(false);
        }
        catch (InvalidDataException)
        {
            return null;
        }
        catch (IOException)
        {
            return null;
        }
        catch (UnauthorizedAccessException)
        {
            return null;
        }
    }

    private static async Task<bool> HasValidFinalizationCandidateAsync(
        string destinationPath,
        FinalizationMarker marker,
        CancellationToken cancellationToken)
    {
        string[] candidates =
        [
            destinationPath,
            TransferArtifactPaths.GetPartialPath(destinationPath),
            TransferArtifactPaths.GetFinalizationStagingPath(destinationPath)
        ];
        foreach (string path in candidates.Distinct(GetPathComparer()))
        {
            if (!File.Exists(path))
            {
                continue;
            }
            try
            {
                await FinalizationFilePromoter
                    .ValidateCandidateAsync(path, marker, cancellationToken)
                    .ConfigureAwait(false);
                return true;
            }
            catch (Exception exception) when (exception is InvalidDataException
                or DownloadIntegrityException
                or IOException
                or UnauthorizedAccessException)
            {
            }
        }
        return false;
    }

    private static string FormatValidatorStatus(string? entityTag, DateTimeOffset? lastModified)
    {
        List<string> validators = [];
        if (!string.IsNullOrWhiteSpace(entityTag))
        {
            validators.Add(entityTag.StartsWith("W/", StringComparison.OrdinalIgnoreCase) ? "weak ETag" : "strong ETag");
        }
        if (lastModified is not null)
        {
            validators.Add("Last-Modified");
        }
        return validators.Count == 0 ? "No persisted validator" : string.Join(" + ", validators);
    }

    private static bool HasUsableValidator(string? entityTag, DateTimeOffset? lastModified)
        => lastModified is not null
            || (!string.IsNullOrWhiteSpace(entityTag)
                && !entityTag.StartsWith("W/", StringComparison.OrdinalIgnoreCase));

    private static string? TryGetDestinationFromArtifact(string path)
    {
        string fullPath = Path.GetFullPath(path);
        foreach (string suffix in ArtifactSuffixes)
        {
            if (fullPath.EndsWith(suffix, StringComparison.OrdinalIgnoreCase))
            {
                return fullPath[..^suffix.Length];
            }
        }
        return null;
    }

    private static void AddRoot(HashSet<string> roots, string? path)
    {
        if (!string.IsNullOrWhiteSpace(path))
        {
            roots.Add(Path.GetFullPath(path));
        }
    }

    private static StringComparer GetPathComparer()
        => OperatingSystem.IsWindows() ? StringComparer.OrdinalIgnoreCase : StringComparer.Ordinal;

    private sealed record RemoteIdentity(
        string? EntityTag,
        DateTimeOffset? LastModified,
        long? Length,
        bool AcceptsRanges);

    private sealed record RemoteValidationResult(
        bool Proven,
        bool Changed,
        string Message);
}
