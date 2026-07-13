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
    public async Task ScanClassifiesDurableCheckpointAsReadyToResume()
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
        using HttpClient client = CreateNoopClient();
        DownloadRecoveryCoordinator coordinator = CreateCoordinator(directory.Path, state, [persisted], client);

        await coordinator.ScanAsync(previousSessionWasUnclean: true, cancellationToken);

        DownloadRecoveryCandidate candidate = Assert.Single(coordinator.Current);
        Assert.Equal(DownloadRecoveryClassification.ReadyToResume, candidate.Classification);
        Assert.Equal(1024, candidate.PartialBytes);
        Assert.True(candidate.CanResume);
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

        await coordinator.ScanAsync(previousSessionWasUnclean: false, cancellationToken);

        DownloadRecoveryCandidate candidate = Assert.Single(coordinator.Current);
        Assert.Equal(DownloadRecoveryClassification.OrphanedArtifact, candidate.Classification);
        Assert.True(candidate.IsOrphaned);
        Assert.Null(candidate.DownloadId);
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
        await coordinator.ScanAsync(previousSessionWasUnclean: true, cancellationToken);

        DownloadRecoveryCandidate validated = await coordinator.ValidateAsync(persisted.Id, cancellationToken);

        Assert.Equal(DownloadRecoveryClassification.RemoteFileChanged, validated.Classification);
        Assert.False(validated.CanResume);
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
