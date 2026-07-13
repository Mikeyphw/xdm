using System.Net;
using System.Net.Http.Headers;
using System.Text.Json;
using XDM.Core.Downloads;
using XDM.Core.Persistence;
using XDM.Core.Settings;
using XDM.Core.State;

namespace XDM.DownloadEngine;

public sealed class DownloadRecoveryCoordinator : IDownloadRecoveryCoordinator
{
    private const int MaximumScannedArtifacts = 4096;
    private static readonly TimeSpan ValidationTimeout = TimeSpan.FromSeconds(15);
    private static readonly string[] ArtifactSuffixes =
    [
        ".xdm.part",
        ".xdm.resume.json",
        ".xdm.finalizing"
    ];
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
        CancellationToken cancellationToken = default)
    {
        IReadOnlyList<PersistedDownload> persisted = await _historyStore
            .LoadAsync(cancellationToken)
            .ConfigureAwait(false);
        Dictionary<string, DownloadSnapshot> snapshots = _applicationState.Current.Downloads
            .ToDictionary(static item => item.Id, StringComparer.Ordinal);
        List<DownloadRecoveryCandidate> candidates = [];
        HashSet<string> knownDestinations = new(GetPathComparer());

        foreach (PersistedDownload item in persisted)
        {
            cancellationToken.ThrowIfCancellationRequested();
            string destinationPath = Path.GetFullPath(item.DestinationPath);
            knownDestinations.Add(destinationPath);
            snapshots.TryGetValue(item.Id, out DownloadSnapshot? snapshot);
            DownloadRecoveryCandidate? candidate = await AssessKnownAsync(
                item,
                snapshot,
                previousSessionWasUnclean,
                cancellationToken).ConfigureAwait(false);
            if (candidate is not null)
            {
                candidates.Add(candidate);
            }
        }

        foreach (string destinationPath in EnumerateArtifactDestinations(GetScanRoots(persisted), cancellationToken))
        {
            if (knownDestinations.Contains(Path.GetFullPath(destinationPath)))
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

        bool changed = HasRemoteChanged(candidate, identity);
        DownloadRecoveryCandidate updated = changed
            ? candidate with
            {
                Classification = DownloadRecoveryClassification.RemoteFileChanged,
                RecommendedAction = "Restart from zero or preserve the partial file before downloading the changed remote object.",
                UnsafeReason = "The server identity or expected length no longer matches the persisted checkpoint.",
                ResumeValidatorStatus = "Remote file changed"
            }
            : candidate with
            {
                Classification = DownloadRecoveryClassification.ReadyToResume,
                RecommendedAction = "Resume the download. XDM will send a validated range request before appending.",
                UnsafeReason = string.Empty,
                ResumeValidatorStatus = identity.AcceptsRanges
                    ? "Validated; byte ranges supported"
                    : "Validated; server did not advertise ranges"
            };
        return Replace(updated);
    }

    public void Dismiss(string candidateId)
    {
        DownloadRecoveryCandidate[] next;
        lock (_sync)
        {
            next = _current
                .Where(candidate => !string.Equals(candidate.Id, candidateId, StringComparison.Ordinal))
                .ToArray();
            _current = next;
        }
        Changed?.Invoke(this, EventArgs.Empty);
    }

    private async Task<DownloadRecoveryCandidate?> AssessKnownAsync(
        PersistedDownload persisted,
        DownloadSnapshot? snapshot,
        bool previousSessionWasUnclean,
        CancellationToken cancellationToken)
    {
        string destination = Path.GetFullPath(persisted.DestinationPath);
        string partialPath = TransferArtifactPaths.GetPartialPath(destination);
        string checkpointPath = TransferArtifactPaths.GetCheckpointPath(destination);
        string finalizationPath = TransferArtifactPaths.GetFinalizationMarkerPath(destination);
        long partialBytes = GetArtifactBytes(destination);
        ResumeCheckpoint? checkpoint = await _checkpointStore.LoadAsync(destination, cancellationToken)
            .ConfigureAwait(false);
        FinalizationMarker? finalization = await ReadFinalizationMarkerAsync(finalizationPath, cancellationToken)
            .ConfigureAwait(false);
        bool wasActive = persisted.State is DownloadState.Connecting
            or DownloadState.Downloading
            or DownloadState.Finalizing;
        bool hasArtifacts = partialBytes > 0
            || File.Exists(checkpointPath)
            || File.Exists(finalizationPath)
            || Directory.Exists(SegmentedDownloadExecutor.GetSegmentDirectory(destination));
        bool recoveredFinalization = snapshot?.State == DownloadState.Completed
            && snapshot.RecoveryMessage?.Contains("interrupted during finalization", StringComparison.OrdinalIgnoreCase) == true;
        if (!wasActive
            && !hasArtifacts
            && snapshot?.RecoveryRequired != true
            && !recoveredFinalization
            && !(previousSessionWasUnclean && persisted.State == DownloadState.Paused && persisted.DownloadedBytes > 0))
        {
            return null;
        }

        DownloadRecoveryClassification classification;
        string action;
        string reason;
        bool finalizationLooksComplete = finalization is not null
            && ((File.Exists(destination) && new FileInfo(destination).Length == finalization.ExpectedLength)
                || partialBytes == finalization.ExpectedLength);
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
        else if (partialBytes > 0 && HasUsableValidator(checkpoint?.EntityTag ?? persisted.EntityTag, checkpoint?.LastModified ?? persisted.LastModified))
        {
            classification = DownloadRecoveryClassification.ReadyToResume;
            action = "Resume the download. XDM will revalidate the server before appending.";
            reason = string.Empty;
        }
        else
        {
            classification = DownloadRecoveryClassification.NeedsRemoteValidation;
            action = "Validate the remote file before resuming.";
            reason = partialBytes > 0
                ? "The partial file has no strong ETag or Last-Modified validator."
                : "The previous session ended while this transfer was active, before durable content was recorded.";
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
            reason);
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
            || Directory.Exists(SegmentedDownloadExecutor.GetSegmentDirectory(destination));
        if (!hasArtifact)
        {
            return null;
        }

        ResumeCheckpoint? checkpoint = await _checkpointStore.LoadAsync(destination, cancellationToken)
            .ConfigureAwait(false);
        FinalizationMarker? finalization = await ReadFinalizationMarkerAsync(finalizationPath, cancellationToken)
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

    private static bool HasRemoteChanged(DownloadRecoveryCandidate candidate, RemoteIdentity remote)
    {
        if (candidate.ExpectedTotalBytes is long expectedLength
            && remote.Length is long actualLength
            && expectedLength != actualLength)
        {
            return true;
        }
        if (!string.IsNullOrWhiteSpace(candidate.EntityTag)
            && !string.IsNullOrWhiteSpace(remote.EntityTag)
            && !string.Equals(candidate.EntityTag, remote.EntityTag, StringComparison.Ordinal))
        {
            return true;
        }
        return candidate.LastModified is DateTimeOffset expectedModified
            && remote.LastModified is DateTimeOffset actualModified
            && expectedModified != actualModified;
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
        string segmentDirectory = SegmentedDownloadExecutor.GetSegmentDirectory(destination);
        if (!Directory.Exists(segmentDirectory))
        {
            return bytes;
        }
        try
        {
            return Math.Max(bytes, Directory.EnumerateFiles(segmentDirectory).Sum(path => new FileInfo(path).Length));
        }
        catch (IOException)
        {
            return bytes;
        }
        catch (UnauthorizedAccessException)
        {
            return bytes;
        }
    }

    private static async Task<FinalizationMarker?> ReadFinalizationMarkerAsync(
        string path,
        CancellationToken cancellationToken)
    {
        if (!File.Exists(path))
        {
            return null;
        }
        try
        {
            await using FileStream stream = File.OpenRead(path);
            FinalizationMarker? marker = await JsonSerializer.DeserializeAsync<FinalizationMarker>(stream, cancellationToken: cancellationToken)
                .ConfigureAwait(false);
            return marker is { Version: FinalizationMarker.CurrentVersion } ? marker : null;
        }
        catch (JsonException)
        {
            return null;
        }
        catch (IOException)
        {
            return null;
        }
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
}
