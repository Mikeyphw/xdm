using System.Net;
using System.Net.Http.Headers;
using XDM.Core.Downloads;
using XDM.Core.Persistence;
using XDM.Core.Settings;
using XDM.Core.State;

namespace XDM.DownloadEngine.Tests;

public sealed class DownloadRecoveryCoordinatorTests
{
    [Fact]
    public async Task ScanRequiresCurrentRemoteValidationBeforeResume()
    {
        CancellationToken cancellationToken = CancellationToken.None;
        using TemporaryDirectory directory = new();
        string destination = Path.Combine(directory.Path, "payload.bin");
        await File.WriteAllBytesAsync(
            TransferArtifactPaths.GetPartialPath(destination),
            new byte[1024],
            cancellationToken);
        PersistedDownload persisted = CreatePersisted(
            "known",
            destination,
            DownloadState.Downloading,
            downloadedBytes: 1024,
            entityTag: "\"v1\"");
        await new ResumeCheckpointStore().SaveAsync(
            new ResumeCheckpoint(
                ResumeCheckpoint.CurrentVersion,
                persisted.Id,
                persisted.Source,
                destination,
                1024,
                4096,
                persisted.EntityTag,
                null,
                1,
                DateTimeOffset.UtcNow),
            cancellationToken);
        ApplicationState state = new();
        state.ReplaceDownloads([CreateSnapshot(persisted, DownloadState.Paused)]);
        using HttpClient client = new(new StubHandler(static _ =>
        {
            HttpResponseMessage response = new(HttpStatusCode.OK)
            {
                Content = new ByteArrayContent([])
            };
            response.Headers.ETag = new EntityTagHeaderValue("\"v1\"");
            response.Headers.AcceptRanges.Add("bytes");
            response.Content.Headers.ContentLength = 4096;
            return response;
        }));
        using DownloadRecoveryCoordinator coordinator = CreateCoordinator(directory.Path, state, [persisted], client);

        await coordinator.ScanAsync(previousSessionWasUnclean: true, cancellationToken: cancellationToken);

        DownloadRecoveryCandidate scanned = Assert.Single(coordinator.Current);
        Assert.Equal(DownloadRecoveryClassification.NeedsRemoteValidation, scanned.Classification);
        Assert.Equal(1024, scanned.PartialBytes);
        Assert.False(scanned.CanResume);

        DownloadRecoveryCandidate validated = await coordinator.ValidateAsync(scanned.Id, cancellationToken);
        Assert.Equal(DownloadRecoveryClassification.ReadyToResume, validated.Classification);
        Assert.True(validated.RemoteIdentityValidated);
        Assert.True(validated.CanResume);
    }

    [Fact]
    public async Task ScanDiscoversOrphanedPartialArtifact()
    {
        CancellationToken cancellationToken = CancellationToken.None;
        using TemporaryDirectory directory = new();
        string destination = Path.Combine(directory.Path, "orphan.bin");
        await File.WriteAllBytesAsync(
            TransferArtifactPaths.GetPartialPath(destination),
            new byte[512],
            cancellationToken);
        ApplicationState state = new();
        using HttpClient client = CreateNoopClient();
        DownloadRecoveryCoordinator coordinator = CreateCoordinator(directory.Path, state, [], client);

        await coordinator.ScanAsync(previousSessionWasUnclean: false, cancellationToken: cancellationToken);

        DownloadRecoveryCandidate candidate = Assert.Single(coordinator.Current);
        Assert.Equal(DownloadRecoveryClassification.OrphanedArtifact, candidate.Classification);
        Assert.True(candidate.IsOrphaned);
        Assert.Null(candidate.DownloadId);
    }

    [Fact]
    public async Task ScanUsesTrackedActiveDownloadsWhenCheckpointFlushFailed()
    {
        using TemporaryDirectory directory = new();
        string destination = Path.Combine(directory.Path, "tracked.bin");
        PersistedDownload persisted = CreatePersisted(
            "tracked",
            destination,
            DownloadState.Paused,
            downloadedBytes: 0,
            entityTag: null);
        ApplicationState state = new();
        state.ReplaceDownloads([CreateSnapshot(persisted, DownloadState.Paused)]);
        using HttpClient client = CreateNoopClient();
        DownloadRecoveryCoordinator coordinator = CreateCoordinator(directory.Path, state, [persisted], client);

        string[] previouslyActive = [persisted.Id];
        await coordinator.ScanAsync(
            previousSessionWasUnclean: true,
            previousActiveDownloadIds: previouslyActive,
            checkpointFlushSucceeded: false,
            cancellationToken: CancellationToken.None);

        DownloadRecoveryCandidate candidate = Assert.Single(coordinator.Current);
        Assert.Equal(DownloadRecoveryClassification.NeedsRemoteValidation, candidate.Classification);
        Assert.Contains("checkpoint flush", candidate.UnsafeReason, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public async Task CleanPreviousSessionDoesNotSurfaceTrackedActiveDownloads()
    {
        using TemporaryDirectory directory = new();
        string destination = Path.Combine(directory.Path, "clean.bin");
        PersistedDownload persisted = CreatePersisted(
            "clean",
            destination,
            DownloadState.Paused,
            downloadedBytes: 0,
            entityTag: null);
        ApplicationState state = new();
        state.ReplaceDownloads([CreateSnapshot(persisted, DownloadState.Paused)]);
        using HttpClient client = CreateNoopClient();
        DownloadRecoveryCoordinator coordinator = CreateCoordinator(directory.Path, state, [persisted], client);

        string[] previouslyActive = [persisted.Id];
        await coordinator.ScanAsync(
            previousSessionWasUnclean: false,
            previousActiveDownloadIds: previouslyActive,
            checkpointFlushSucceeded: true,
            cancellationToken: CancellationToken.None);

        Assert.Empty(coordinator.Current);
    }

    [Fact]
    public async Task ValidateDetectsChangedRemoteEntityTag()
    {
        CancellationToken cancellationToken = CancellationToken.None;
        using TemporaryDirectory directory = new();
        string destination = Path.Combine(directory.Path, "changed.bin");
        await File.WriteAllBytesAsync(
            TransferArtifactPaths.GetPartialPath(destination),
            new byte[1024],
            cancellationToken);
        PersistedDownload persisted = CreatePersisted(
            "changed",
            destination,
            DownloadState.Paused,
            downloadedBytes: 1024,
            entityTag: "\"old\"");
        ApplicationState state = new();
        state.ReplaceDownloads([CreateSnapshot(persisted, DownloadState.Paused)]);
        using HttpClient client = new(new StubHandler(static request =>
        {
            HttpResponseMessage response = new(HttpStatusCode.OK)
            {
                Content = new ByteArrayContent([])
            };
            response.Headers.ETag = new EntityTagHeaderValue("\"new\"");
            response.Headers.AcceptRanges.Add("bytes");
            response.Content.Headers.ContentLength = 4096;
            return response;
        }));
        DownloadRecoveryCoordinator coordinator = CreateCoordinator(directory.Path, state, [persisted], client);
        await coordinator.ScanAsync(previousSessionWasUnclean: true, cancellationToken: cancellationToken);

        DownloadRecoveryCandidate validated = await coordinator.ValidateAsync(persisted.Id, cancellationToken);

        Assert.Equal(DownloadRecoveryClassification.RemoteFileChanged, validated.Classification);
        Assert.False(validated.CanResume);
    }

    [Fact]
    public async Task ScanDiscoversSegmentOnlyOrphanArtifact()
    {
        using TemporaryDirectory directory = new();
        string destination = Path.Combine(directory.Path, "segment-only.bin");
        string segmentDirectory = SegmentedDownloadExecutor.GetSegmentDirectory(destination);
        Directory.CreateDirectory(segmentDirectory);
        await File.WriteAllBytesAsync(Path.Combine(segmentDirectory, "0000.part"), new byte[321]);
        ApplicationState state = new();
        using HttpClient client = CreateNoopClient();
        using DownloadRecoveryCoordinator coordinator = CreateCoordinator(directory.Path, state, [], client);

        await coordinator.ScanAsync(previousSessionWasUnclean: false);

        DownloadRecoveryCandidate candidate = Assert.Single(coordinator.Current);
        Assert.True(candidate.IsOrphaned);
        Assert.Equal(321, candidate.PartialBytes);
        Assert.Equal(destination, candidate.DestinationPath);
    }

    [Fact]
    public async Task PersistentDismissalPreventsRecoveryRecordFromReturningOnNextScan()
    {
        using TemporaryDirectory directory = new();
        string destination = Path.Combine(directory.Path, "dismiss.bin");
        await File.WriteAllBytesAsync(TransferArtifactPaths.GetPartialPath(destination), new byte[64]);
        ApplicationState state = new();
        using HttpClient client = CreateNoopClient();
        using DownloadRecoveryCoordinator coordinator = CreateCoordinator(directory.Path, state, [], client);

        await coordinator.ScanAsync(previousSessionWasUnclean: false);
        DownloadRecoveryCandidate candidate = Assert.Single(coordinator.Current);
        await coordinator.DismissAsync(candidate.Id, persist: true);
        Assert.Empty(coordinator.Current);

        await coordinator.ScanAsync(previousSessionWasUnclean: false);
        Assert.Empty(coordinator.Current);
        Assert.True(File.Exists(TransferArtifactPaths.GetRecoveryDismissalPath(destination)));
    }

    [Fact]
    public async Task UncleanAria2DestinationIsNotMisclassifiedAsMissingPartial()
    {
        using TemporaryDirectory directory = new();
        string destination = Path.Combine(directory.Path, "aria2.bin");
        await File.WriteAllBytesAsync(destination, new byte[512]);
        PersistedDownload persisted = CreatePersisted(
            "aria2",
            destination,
            DownloadState.Downloading,
            downloadedBytes: 512,
            entityTag: "\"aria2-v1\"") with
        {
            Backend = DownloadBackendKind.Aria2,
            BackendTaskId = "0123456789abcdef"
        };
        ApplicationState state = new();
        state.ReplaceDownloads([CreateSnapshot(persisted, DownloadState.Paused)]);
        using HttpClient client = CreateNoopClient();
        using DownloadRecoveryCoordinator coordinator = CreateCoordinator(directory.Path, state, [persisted], client);

        await coordinator.ScanAsync(previousSessionWasUnclean: true);

        DownloadRecoveryCandidate candidate = Assert.Single(coordinator.Current);
        Assert.NotEqual(DownloadRecoveryClassification.MissingPartialFile, candidate.Classification);
        Assert.Equal(512, candidate.PartialBytes);
        Assert.Equal(destination, candidate.PartialPath);
    }

    [Fact]
    public async Task ResumeCandidateRemainsTrackedUntilDownloadCompletesSafely()
    {
        using TemporaryDirectory directory = new();
        string destination = Path.Combine(directory.Path, "resume-tracked.bin");
        await File.WriteAllBytesAsync(TransferArtifactPaths.GetPartialPath(destination), new byte[256]);
        PersistedDownload persisted = CreatePersisted(
            "resume-tracked",
            destination,
            DownloadState.Paused,
            downloadedBytes: 256,
            entityTag: "\"resume-v1\"");
        ApplicationState state = new();
        state.ReplaceDownloads([CreateSnapshot(persisted, DownloadState.Paused)]);
        using HttpClient client = new(new StubHandler(static _ =>
        {
            HttpResponseMessage response = new(HttpStatusCode.OK)
            {
                Content = new ByteArrayContent([])
            };
            response.Headers.ETag = new EntityTagHeaderValue("\"resume-v1\"");
            response.Headers.AcceptRanges.Add("bytes");
            response.Content.Headers.ContentLength = 4096;
            return response;
        }));
        using DownloadRecoveryCoordinator coordinator = CreateCoordinator(directory.Path, state, [persisted], client);
        await coordinator.ScanAsync(previousSessionWasUnclean: true);
        DownloadRecoveryCandidate candidate = await coordinator.ValidateAsync(persisted.Id);
        Assert.True(candidate.CanResume);

        coordinator.MarkResumeStarted(candidate.Id);
        DownloadRecoveryCandidate running = Assert.Single(coordinator.Current);
        Assert.Equal(DownloadRecoveryClassification.ResumeInProgress, running.Classification);
        Assert.False(running.CanResume);

        state.ReplaceDownloads([CreateSnapshot(persisted, DownloadState.Downloading)]);
        Assert.Single(coordinator.Current);
        state.ReplaceDownloads([CreateSnapshot(persisted, DownloadState.Completed)]);
        Assert.Empty(coordinator.Current);
    }

    private static DownloadRecoveryCoordinator CreateCoordinator(
        string directory,
        ApplicationState state,
        PersistedDownload[] downloads,
        HttpClient client)
        => new(
            state,
            new InMemoryHistoryStore(downloads),
            new StubSettingsService(ApplicationSettings.CreateDefault() with
            {
                DefaultDownloadDirectory = directory,
                Categories = [new DownloadCategoryDefinition("general", "General", [], directory)]
            }),
            client);

    private static HttpClient CreateNoopClient()
        => new(new StubHandler(static _ => new HttpResponseMessage(HttpStatusCode.OK)
        {
            Content = new ByteArrayContent([])
        }));

    private static PersistedDownload CreatePersisted(
        string id,
        string destination,
        DownloadState state,
        long downloadedBytes,
        string? entityTag)
        => new(
            id,
            new Uri($"https://example.test/{id}.bin"),
            destination,
            downloadedBytes,
            4096,
            state,
            DateTimeOffset.UtcNow,
            EntityTag: entityTag);

    private static DownloadSnapshot CreateSnapshot(PersistedDownload persisted, DownloadState state)
        => new(
            persisted.Id,
            Path.GetFileName(persisted.DestinationPath),
            persisted.Source,
            persisted.DestinationPath,
            persisted.DownloadedBytes,
            persisted.TotalBytes,
            0,
            state,
            persisted.UpdatedAt);

    private sealed class InMemoryHistoryStore(PersistedDownload[] downloads) : IDownloadHistoryStore
    {
        public Task<IReadOnlyList<PersistedDownload>> LoadAsync(CancellationToken cancellationToken = default)
        {
            cancellationToken.ThrowIfCancellationRequested();
            return Task.FromResult<IReadOnlyList<PersistedDownload>>(downloads);
        }

        public Task SaveAsync(
            IReadOnlyCollection<PersistedDownload> items,
            CancellationToken cancellationToken = default)
        {
            cancellationToken.ThrowIfCancellationRequested();
            return Task.CompletedTask;
        }
    }

    private sealed class StubSettingsService(ApplicationSettings settings) : ISettingsService
    {
        public ApplicationSettings Current { get; private set; } = settings.Normalize();

        public event EventHandler<ApplicationSettings>? Changed
        {
            add { }
            remove { }
        }

        public Task InitializeAsync(CancellationToken cancellationToken = default)
        {
            cancellationToken.ThrowIfCancellationRequested();
            return Task.CompletedTask;
        }

        public Task UpdateAsync(ApplicationSettings settings, CancellationToken cancellationToken = default)
        {
            cancellationToken.ThrowIfCancellationRequested();
            Current = settings.Normalize();
            return Task.CompletedTask;
        }
    }

    private sealed class StubHandler(Func<HttpRequestMessage, HttpResponseMessage> responder) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            return Task.FromResult(responder(request));
        }
    }

    private sealed class TemporaryDirectory : IDisposable
    {
        public TemporaryDirectory()
        {
            Path = System.IO.Path.Combine(
                System.IO.Path.GetTempPath(),
                $"xdm-recovery-coordinator-{Guid.NewGuid():N}");
            Directory.CreateDirectory(Path);
        }

        public string Path { get; }

        public void Dispose()
        {
            if (Directory.Exists(Path))
            {
                Directory.Delete(Path, recursive: true);
            }
        }
    }
}
