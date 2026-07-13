using System.Net;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using Microsoft.Extensions.Logging.Abstractions;
using XDM.Core.Downloads;
using XDM.Core.Persistence;
using XDM.Core.Settings;
using XDM.Core.State;

using XDM.DownloadEngine.Aria2;
namespace XDM.DownloadEngine.Tests;

public sealed class DownloadManagerTests
{
    [Fact]
    public async Task CompletesDownloadAndPublishesState()
    {
        byte[] payload = CreatePayload(4096, 251);
        using TemporaryDirectory directory = new();
        using HttpClient client = new(new RangeHandler(payload));
        ApplicationState state = new();
        InMemoryHistoryStore history = new();
        using DownloadManager manager = CreateManager(client, state, history);

        string id = await manager.AddAsync(new DownloadRequest(
            new Uri("https://example.test/payload.bin"),
            directory.Path,
            "payload.bin"));

        DownloadSnapshot completed = await WaitForStateAsync(state, id, DownloadState.Completed);

        Assert.Equal(payload.Length, completed.DownloadedBytes);
        Assert.Equal(payload, await File.ReadAllBytesAsync(Path.Combine(directory.Path, "payload.bin")));
    }

    [Fact]
    public async Task ResumesFromExistingPartialFile()
    {
        byte[] payload = CreatePayload(8192, 239);
        const int partialLength = 2048;
        using TemporaryDirectory directory = new();
        string destination = Path.Combine(directory.Path, "resume.bin");
        Uri source = new("https://example.test/resume.bin");
        await File.WriteAllBytesAsync($"{destination}.part", payload[..partialLength]);
        await new ResumeCheckpointStore().SaveAsync(new ResumeCheckpoint(
            ResumeCheckpoint.CurrentVersion,
            "legacy-resume",
            source,
            destination,
            partialLength,
            payload.Length,
            null,
            null,
            1,
            DateTimeOffset.UtcNow));

        RangeHandler handler = new(payload);
        using HttpClient client = new(handler);
        ApplicationState state = new();
        using DownloadManager manager = CreateManager(client, state, new InMemoryHistoryStore());

        string id = await manager.AddAsync(new DownloadRequest(
            source,
            directory.Path,
            "resume.bin"));

        await WaitForStateAsync(state, id, DownloadState.Completed);

        Assert.Equal(partialLength, handler.LastRangeStart);
        Assert.Equal(payload, await File.ReadAllBytesAsync(destination));
    }

    [Fact]
    public async Task RestartsSafelyWhenServerIgnoresRange()
    {
        byte[] payload = CreatePayload(4096, 193);
        using TemporaryDirectory directory = new();
        string destination = Path.Combine(directory.Path, "ignored-range.bin");
        Uri source = new("https://example.test/ignored-range.bin");
        await File.WriteAllBytesAsync($"{destination}.part", payload[..1024]);
        await new ResumeCheckpointStore().SaveAsync(new ResumeCheckpoint(
            ResumeCheckpoint.CurrentVersion,
            "legacy-ignored-range",
            source,
            destination,
            1024,
            payload.Length,
            null,
            null,
            1,
            DateTimeOffset.UtcNow));

        IgnoreRangeHandler handler = new(payload);
        using HttpClient client = new(handler);
        ApplicationState state = new();
        using DownloadManager manager = CreateManager(client, state, new InMemoryHistoryStore());

        string id = await manager.AddAsync(new DownloadRequest(
            source,
            directory.Path,
            "ignored-range.bin"));

        await WaitForStateAsync(state, id, DownloadState.Completed);

        Assert.Equal(1024, handler.RequestedRangeStart);
        Assert.Equal(payload, await File.ReadAllBytesAsync(destination));
    }


    [Fact]
    public async Task PreservesOrphanedPartialWhenCheckpointSourceDoesNotMatch()
    {
        byte[] oldPayload = CreatePayload(1024, 61);
        byte[] newPayload = CreatePayload(2048, 59);
        using TemporaryDirectory directory = new();
        string destination = Path.Combine(directory.Path, "orphan.bin");
        string partialPath = TransferArtifactPaths.GetPartialPath(destination);
        await File.WriteAllBytesAsync(partialPath, oldPayload);
        await new ResumeCheckpointStore().SaveAsync(new ResumeCheckpoint(
            ResumeCheckpoint.CurrentVersion,
            "orphan",
            new Uri("https://old.example.test/orphan.bin"),
            destination,
            oldPayload.Length,
            oldPayload.Length * 2,
            null,
            null,
            1,
            DateTimeOffset.UtcNow));
        RangeHandler handler = new(newPayload);
        using HttpClient client = new(handler);
        ApplicationState state = new();
        using DownloadManager manager = CreateManager(client, state, new InMemoryHistoryStore());

        string id = await manager.AddAsync(new DownloadRequest(
            new Uri("https://new.example.test/orphan.bin"),
            directory.Path,
            "orphan.bin",
            ConnectionCount: 1));
        await WaitForStateAsync(state, id, DownloadState.Completed);

        Assert.Null(handler.LastRangeStart);
        Assert.Equal(newPayload, await File.ReadAllBytesAsync(destination));
        string preservedPartial = Assert.Single(Directory.EnumerateFiles(directory.Path, "orphan.bin.stale-*.xdm.part"));
        Assert.Equal(oldPayload, await File.ReadAllBytesAsync(preservedPartial));
    }

    [Fact]
    public async Task UsesPersistedEntityTagAsIfRangeValidator()
    {
        byte[] payload = CreatePayload(4096, 181);
        const int partialLength = 1024;
        using TemporaryDirectory directory = new();
        string destination = Path.Combine(directory.Path, "validated.bin");
        await File.WriteAllBytesAsync($"{destination}.part", payload[..partialLength]);

        PersistedDownload persisted = new(
            "validated",
            new Uri("https://example.test/validated.bin"),
            destination,
            partialLength,
            payload.Length,
            DownloadState.Paused,
            DateTimeOffset.UtcNow,
            EntityTag: "\"stable-v1\"");
        InMemoryHistoryStore history = new([persisted]);
        RangeHandler handler = new(payload, "\"stable-v1\"");
        using HttpClient client = new(handler);
        ApplicationState state = new();
        using DownloadManager manager = CreateManager(client, state, history);

        await manager.InitializeAsync();
        await manager.ResumeAsync("validated");
        await WaitForStateAsync(state, "validated", DownloadState.Completed);

        Assert.Equal("\"stable-v1\"", handler.LastIfRangeEntityTag);
        Assert.Equal(payload, await File.ReadAllBytesAsync(destination));
    }

    [Fact]
    public async Task RejectsChangedEntityTagDuringResume()
    {
        byte[] payload = CreatePayload(4096, 173);
        const int partialLength = 1024;
        using TemporaryDirectory directory = new();
        string destination = Path.Combine(directory.Path, "changed.bin");
        string partialPath = TransferArtifactPaths.GetPartialPath(destination);
        await File.WriteAllBytesAsync(partialPath, payload[..partialLength]);

        PersistedDownload persisted = new(
            "changed",
            new Uri("https://example.test/changed.bin"),
            destination,
            partialLength,
            payload.Length,
            DownloadState.Paused,
            DateTimeOffset.UtcNow,
            EntityTag: "\"old\"");
        InMemoryHistoryStore history = new([persisted]);
        RangeHandler handler = new(payload, "\"new\"");
        using HttpClient client = new(handler);
        ApplicationState state = new();
        using DownloadManager manager = CreateManager(client, state, history);

        await manager.InitializeAsync();
        await manager.ResumeAsync("changed");
        DownloadSnapshot failed = await WaitForStateAsync(state, "changed", DownloadState.Failed);

        Assert.Contains("entity tag changed", failed.ErrorMessage, StringComparison.OrdinalIgnoreCase);
        Assert.Equal(partialLength, new FileInfo(partialPath).Length);
        Assert.False(File.Exists(destination));
    }

    [Fact]
    public async Task RetriesTruncatedResponseAndResumesFromCheckpoint()
    {
        byte[] payload = CreatePayload(8192, 167);
        using TemporaryDirectory directory = new();
        TruncatedThenRangeHandler handler = new(payload);
        using HttpClient client = new(handler);
        ApplicationState state = new();
        using DownloadManager manager = CreateManager(
            client,
            state,
            new InMemoryHistoryStore(),
            retryPolicy: new DownloadRetryPolicy(3, TimeSpan.FromMilliseconds(1), 0));

        string id = await manager.AddAsync(new DownloadRequest(
            new Uri("https://example.test/retry.bin"),
            directory.Path,
            "retry.bin",
            ConnectionCount: 1));

        await WaitForStateAsync(state, id, DownloadState.Completed);

        Assert.Equal(2, handler.RequestCount);
        Assert.Equal(payload.Length / 2, handler.SecondRangeStart);
        Assert.Equal(payload, await File.ReadAllBytesAsync(Path.Combine(directory.Path, "retry.bin")));
    }

    [Fact]
    public async Task FailsBeforeWritingWhenDiskSpaceIsInsufficient()
    {
        byte[] payload = CreatePayload(4096, 157);
        using TemporaryDirectory directory = new();
        using HttpClient client = new(new RangeHandler(payload));
        ApplicationState state = new();
        using DownloadManager manager = CreateManager(
            client,
            state,
            new InMemoryHistoryStore(),
            new FixedDiskSpaceProvider(1));

        string id = await manager.AddAsync(new DownloadRequest(
            new Uri("https://example.test/no-space.bin"),
            directory.Path,
            "no-space.bin"));

        DownloadSnapshot failed = await WaitForStateAsync(state, id, DownloadState.Failed);

        Assert.Contains("Not enough free space", failed.ErrorMessage, StringComparison.Ordinal);
        Assert.False(File.Exists(TransferArtifactPaths.GetPartialPath(Path.Combine(directory.Path, "no-space.bin"))));
    }

    [Fact]
    public async Task RecoversInterruptedFinalizationOnStartup()
    {
        byte[] payload = CreatePayload(2048, 149);
        using TemporaryDirectory directory = new();
        string destination = Path.Combine(directory.Path, "recover.bin");
        await File.WriteAllBytesAsync($"{destination}.part", payload);
        await File.WriteAllTextAsync($"{destination}.finalizing", payload.Length.ToString(System.Globalization.CultureInfo.InvariantCulture));

        PersistedDownload persisted = new(
            "recover",
            new Uri("https://example.test/recover.bin"),
            destination,
            payload.Length,
            payload.Length,
            DownloadState.Finalizing,
            DateTimeOffset.UtcNow);
        ApplicationState state = new();
        using HttpClient client = new(new RangeHandler(payload));
        using DownloadManager manager = CreateManager(client, state, new InMemoryHistoryStore([persisted]));

        await manager.InitializeAsync();

        DownloadSnapshot completed = state.Current.Downloads.Single(item => item.Id == "recover");
        Assert.Equal(DownloadState.Completed, completed.State);
        Assert.Equal(payload, await File.ReadAllBytesAsync(destination));
        Assert.False(File.Exists($"{destination}.finalizing"));
    }

    [Fact]
    public async Task StartsNonDefaultQueueOnlyWhenActivated()
    {
        byte[] payload = [7, 8, 9, 10];
        using TemporaryDirectory directory = new();
        using HttpClient client = new(new RangeHandler(payload));
        ApplicationState state = new();
        using DownloadManager manager = CreateManager(client, state, new InMemoryHistoryStore());

        string id = await manager.AddAsync(new DownloadRequest(
            new Uri("https://example.test/queued.bin"),
            directory.Path,
            "queued.bin",
            QueueId: "night"));

        Assert.Equal(DownloadState.Queued, state.Current.Downloads.Single(item => item.Id == id).State);
        Assert.Equal("night", state.Current.Downloads.Single(item => item.Id == id).QueueId);

        await manager.StartQueueAsync("night");
        DownloadSnapshot completed = await WaitForStateAsync(state, id, DownloadState.Completed);

        Assert.Equal("night", completed.QueueId);
        Assert.Contains("night", manager.QueueRuntime.ActiveQueueIds);
    }

    [Fact]
    public async Task PersistsAndPublishesPerDownloadPriority()
    {
        using TemporaryDirectory directory = new();
        byte[] payload = [1, 2, 3];
        using HttpClient client = new(new RangeHandler(payload));
        ApplicationState state = new();
        InMemoryHistoryStore history = new();
        using DownloadManager manager = CreateManager(client, state, history);

        string id = await manager.AddAsync(new DownloadRequest(
            new Uri("https://example.test/priority.bin"),
            directory.Path,
            "priority.bin",
            QueueId: "night",
            Priority: DownloadPriority.High));

        Assert.Equal(DownloadPriority.High, state.Current.Downloads.Single(item => item.Id == id).Priority);
        await manager.SetPriorityAsync(id, DownloadPriority.Low);

        Assert.Equal(DownloadPriority.Low, state.Current.Downloads.Single(item => item.Id == id).Priority);
        Assert.Equal(DownloadPriority.Low, history.Downloads.Single(item => item.Id == id).Priority);
    }

    [Fact]
    public async Task AppliesRequestMetadataAndRenamesCollisions()
    {
        byte[] payload = [1, 2, 3, 4];
        using TemporaryDirectory directory = new();
        await File.WriteAllTextAsync(Path.Combine(directory.Path, "payload.bin"), "existing");
        RangeHandler handler = new(payload);
        using HttpClient client = new(handler);
        ApplicationState state = new();
        using DownloadManager manager = CreateManager(client, state, new InMemoryHistoryStore());

        string id = await manager.AddAsync(new DownloadRequest(
            new Uri("https://example.test/payload.bin"),
            directory.Path,
            "payload.bin",
            new Dictionary<string, string> { ["X-Test"] = "value" },
            "user",
            "password",
            "session=abc",
            "https://example.test/page",
            "XDM-Test"));

        DownloadSnapshot completed = await WaitForStateAsync(state, id, DownloadState.Completed);

        Assert.Equal("payload (1).bin", completed.FileName);
        Assert.Equal("value", handler.LastTestHeader);
        Assert.Equal("Basic", handler.LastAuthorizationScheme);
        Assert.Equal("session=abc", handler.LastCookie);
    }

    [Fact]
    public async Task RestoredPostCaptureIsNotReplayedWithoutEphemeralBody()
    {
        using TemporaryDirectory directory = new();
        PersistedDownload persisted = new(
            "post-restored",
            new Uri("https://example.test/export"),
            Path.Combine(directory.Path, "export.bin"),
            0,
            null,
            DownloadState.Queued,
            DateTimeOffset.UtcNow,
            Method: "POST");
        PostHandler handler = new([1, 2, 3]);
        using HttpClient client = new(handler);
        ApplicationState state = new();
        using DownloadManager manager = CreateManager(client, state, new InMemoryHistoryStore([persisted]));

        await manager.InitializeAsync();
        DownloadSnapshot restored = Assert.Single(state.Current.Downloads);
        await manager.RetryAsync(restored.Id);
        await Task.Delay(50);

        DownloadSnapshot current = Assert.Single(state.Current.Downloads);
        Assert.Equal(DownloadState.Failed, current.State);
        Assert.NotNull(current.ErrorMessage);
        Assert.Contains("cannot be replayed", current.ErrorMessage, StringComparison.OrdinalIgnoreCase);
        Assert.Equal(0, handler.RequestCount);
    }

    [Fact]
    public async Task SendsCapturedPostBodyWithoutSegmentingOrRetrying()
    {
        byte[] responsePayload = [9, 8, 7, 6];
        byte[] requestBody = System.Text.Encoding.UTF8.GetBytes("token=abc&format=zip");
        using TemporaryDirectory directory = new();
        PostHandler handler = new(responsePayload);
        using HttpClient client = new(handler);
        ApplicationState state = new();
        using DownloadManager manager = CreateManager(client, state, new InMemoryHistoryStore());

        string id = await manager.AddAsync(new DownloadRequest(
            new Uri("https://example.test/export"),
            directory.Path,
            "export.bin",
            ConnectionCount: 8,
            Method: "POST",
            RequestBody: requestBody,
            RequestBodyContentType: "application/x-www-form-urlencoded"));

        await WaitForStateAsync(state, id, DownloadState.Completed);

        Assert.Equal(HttpMethod.Post, handler.Method);
        Assert.Equal(requestBody, handler.Body);
        Assert.Equal("application/x-www-form-urlencoded", handler.ContentType);
        Assert.Null(handler.RangeStart);
        Assert.Equal(1, handler.RequestCount);
    }

    [Fact]
    public async Task RelocatesCompletedDownloadAndPersistsNewPath()
    {
        byte[] payload = CreatePayload(2048, 127);
        using TemporaryDirectory directory = new();
        using HttpClient client = new(new RangeHandler(payload));
        ApplicationState state = new();
        InMemoryHistoryStore history = new();
        using DownloadManager manager = CreateManager(client, state, history);
        string id = await manager.AddAsync(new DownloadRequest(
            new Uri("https://example.test/move.bin"),
            directory.Path,
            "move.bin",
            SourcePage: new Uri("https://example.test/source-page")));
        await WaitForStateAsync(state, id, DownloadState.Completed);
        string target = Path.Combine(directory.Path, "renamed.bin");

        await manager.RelocateAsync(id, target);

        DownloadSnapshot snapshot = Assert.Single(state.Current.Downloads, item => item.Id == id);
        Assert.Equal(target, snapshot.DestinationPath);
        Assert.True(File.Exists(target));
        Assert.Equal(target, Assert.Single(history.Downloads, item => item.Id == id).DestinationPath);
    }

    [Fact]
    public async Task RefreshesExpiredUrlAndPreservesPartialDestination()
    {
        using TemporaryDirectory directory = new();
        string destination = Path.Combine(directory.Path, "expired.bin");
        byte[] partialPayload = [1, 2, 3];
        await File.WriteAllBytesAsync($"{destination}.part", partialPayload);
        PersistedDownload persisted = new(
            "expired",
            new Uri("https://old.example.test/file.bin"),
            destination,
            3,
            100,
            DownloadState.Failed,
            DateTimeOffset.UtcNow,
            ErrorMessage: "Expired");
        ApplicationState state = new();
        InMemoryHistoryStore history = new([persisted]);
        using HttpClient client = new(new RangeHandler(CreatePayload(100, 73)));
        using DownloadManager manager = CreateManager(client, state, history);
        await manager.InitializeAsync();
        Uri replacement = new("https://new.example.test/file.bin");
        Uri sourcePage = new("https://new.example.test/page");

        await manager.RefreshSourceAsync("expired", replacement, sourcePage);

        DownloadSnapshot snapshot = Assert.Single(state.Current.Downloads);
        Assert.Equal(replacement, snapshot.Source);
        Assert.Equal(sourcePage, snapshot.SourcePage);
        Assert.Equal(DownloadState.Paused, snapshot.State);
        Assert.True(File.Exists(TransferArtifactPaths.GetPartialPath(destination)));
    }

    [Fact]
    public async Task DistinguishesHistoryRemovalFromDownloadedFileDeletion()
    {
        byte[] payload = CreatePayload(1024, 61);
        using TemporaryDirectory directory = new();
        using HttpClient client = new(new RangeHandler(payload));
        ApplicationState state = new();
        using DownloadManager manager = CreateManager(client, state, new InMemoryHistoryStore());
        string keepId = await manager.AddAsync(new DownloadRequest(
            new Uri("https://example.test/keep.bin"),
            directory.Path,
            "keep.bin"));
        await WaitForStateAsync(state, keepId, DownloadState.Completed);
        string keepPath = Path.Combine(directory.Path, "keep.bin");

        await manager.DeleteAsync(keepId, DownloadDeletionScope.HistoryOnly);

        Assert.True(File.Exists(keepPath));
        string deleteId = await manager.AddAsync(new DownloadRequest(
            new Uri("https://example.test/delete.bin"),
            directory.Path,
            "delete.bin"));
        await WaitForStateAsync(state, deleteId, DownloadState.Completed);
        string deletePath = Path.Combine(directory.Path, "delete.bin");

        await manager.DeleteAsync(deleteId, DownloadDeletionScope.HistoryAndDownloadedFile);

        Assert.False(File.Exists(deletePath));
        Assert.DoesNotContain(state.Current.Downloads, item => item.Id == deleteId);
    }

    [Fact]
    public async Task UndoRestoresMostRecentlyRemovedHistoryEntry()
    {
        byte[] payload = CreatePayload(1024, 67);
        using TemporaryDirectory directory = new();
        using HttpClient client = new(new RangeHandler(payload));
        ApplicationState state = new();
        InMemoryHistoryStore history = new();
        using DownloadManager manager = CreateManager(client, state, history);
        string id = await manager.AddAsync(new DownloadRequest(
            new Uri("https://example.test/undo.bin"),
            directory.Path,
            "undo.bin"));
        await WaitForStateAsync(state, id, DownloadState.Completed);

        await manager.DeleteAsync(id, DownloadDeletionScope.HistoryOnly);

        Assert.Empty(state.Current.Downloads);
        Assert.Equal(1, manager.UndoableRemovalCount);
        Assert.True(File.Exists(Path.Combine(directory.Path, "undo.bin")));

        string? restoredId = await manager.UndoLastRemovalAsync();

        Assert.Equal(id, restoredId);
        Assert.Equal(0, manager.UndoableRemovalCount);
        DownloadSnapshot restored = Assert.Single(state.Current.Downloads);
        Assert.Equal(id, restored.Id);
        Assert.Equal(DownloadState.Completed, restored.State);
        Assert.Equal(id, Assert.Single(history.Downloads).Id);
    }

    [Fact]
    public async Task CancelledUndoLeavesRemovalAvailable()
    {
        byte[] payload = CreatePayload(256, 71);
        using TemporaryDirectory directory = new();
        using HttpClient client = new(new RangeHandler(payload));
        ApplicationState state = new();
        using DownloadManager manager = CreateManager(client, state, new InMemoryHistoryStore());
        string id = await manager.AddAsync(new DownloadRequest(
            new Uri("https://example.test/cancelled-undo.bin"),
            directory.Path,
            "cancelled-undo.bin"));
        await WaitForStateAsync(state, id, DownloadState.Completed);
        await manager.DeleteAsync(id, DownloadDeletionScope.HistoryOnly);
        using CancellationTokenSource cancellation = new();
        cancellation.Cancel();

        await Assert.ThrowsAnyAsync<OperationCanceledException>(
            () => manager.UndoLastRemovalAsync(cancellation.Token));

        Assert.Equal(1, manager.UndoableRemovalCount);
        Assert.Empty(state.Current.Downloads);
    }

    [Fact]
    public async Task RedownloadCreatesNewEntryWithSourcePageAndAutoRename()
    {
        byte[] payload = CreatePayload(512, 41);
        using TemporaryDirectory directory = new();
        using HttpClient client = new(new RangeHandler(payload));
        ApplicationState state = new();
        using DownloadManager manager = CreateManager(client, state, new InMemoryHistoryStore());
        Uri sourcePage = new("https://example.test/page");
        string originalId = await manager.AddAsync(new DownloadRequest(
            new Uri("https://example.test/repeat.bin"),
            directory.Path,
            "repeat.bin",
            SourcePage: sourcePage));
        await WaitForStateAsync(state, originalId, DownloadState.Completed);

        string newId = await manager.RedownloadAsync(originalId);
        DownloadSnapshot repeated = await WaitForStateAsync(state, newId, DownloadState.Completed);

        Assert.NotEqual(originalId, newId);
        Assert.Equal(sourcePage, repeated.SourcePage);
        Assert.Equal(Path.Combine(directory.Path, "repeat (1).bin"), repeated.DestinationPath);
        Assert.True(File.Exists(Path.Combine(directory.Path, "repeat.bin")));
        Assert.True(File.Exists(repeated.DestinationPath));
    }

    [Fact]
    public async Task RestoresLargeHistoryInSingleApplicationStatePublication()
    {
        const int historySize = 10_000;
        using TemporaryDirectory directory = new();
        DateTimeOffset now = DateTimeOffset.UtcNow;
        PersistedDownload[] persisted = new PersistedDownload[historySize];
        for (int index = 0; index < persisted.Length; index++)
        {
            persisted[index] = new PersistedDownload(
                $"history-{index}",
                new Uri($"https://example.test/{index}.bin"),
                Path.Combine(directory.Path, $"{index}.bin"),
                0,
                null,
                DownloadState.Paused,
                now.AddSeconds(-index));
        }

        ApplicationState state = new();
        int publicationCount = 0;
        state.Changed += (_, _) => publicationCount++;
        using HttpClient client = new(new RangeHandler(CreatePayload(32, 17)));
        using DownloadManager manager = CreateManager(client, state, new InMemoryHistoryStore(persisted));

        await manager.InitializeAsync();

        Assert.Equal(historySize, state.Current.Downloads.Count);
        Assert.Equal(1, publicationCount);
        Assert.Equal("history-0", state.Current.Downloads[0].Id);
    }


    [Fact]
    public async Task RejectsResponseThatDoesNotMatchDeclaredLength()
    {
        byte[] payload = CreatePayload(1024, 67);
        using TemporaryDirectory directory = new();
        using HttpClient client = new(new RangeHandler(payload));
        ApplicationState state = new();
        using DownloadManager manager = CreateManager(client, state, new InMemoryHistoryStore());

        string id = await manager.AddAsync(new DownloadRequest(
            new Uri("https://example.test/declared-length.bin"),
            directory.Path,
            "declared-length.bin",
            ConnectionCount: 1,
            ExpectedLength: payload.Length * 2L));

        DownloadSnapshot failed = await WaitForStateAsync(state, id, DownloadState.Failed);

        Assert.Contains("length changed", failed.ErrorMessage, StringComparison.OrdinalIgnoreCase);
        Assert.False(File.Exists(failed.DestinationPath));
        Assert.False(File.Exists(TransferArtifactPaths.GetPartialPath(failed.DestinationPath)));
    }

    [Fact]
    public async Task AutomaticallyVerifiesExpectedChecksumBeforeFinalization()
    {
        byte[] payload = CreatePayload(4096, 113);
        string expected = Convert.ToHexString(SHA256.HashData(payload));
        using TemporaryDirectory directory = new();
        using HttpClient client = new(new RangeHandler(payload));
        ApplicationState state = new();
        using DownloadManager manager = CreateManager(client, state, new InMemoryHistoryStore());

        string id = await manager.AddAsync(new DownloadRequest(
            new Uri("https://example.test/verified.bin"),
            directory.Path,
            "verified.bin",
            ConnectionCount: 1,
            ExpectedChecksumAlgorithm: DownloadChecksumService.Sha256,
            ExpectedChecksum: expected));

        DownloadSnapshot completed = await WaitForStateAsync(state, id, DownloadState.Completed);

        Assert.Equal(DownloadIntegrityStatus.Verified, completed.IntegrityStatus);
        Assert.Equal(expected, completed.ActualChecksum);
        Assert.False(completed.RecoveryRequired);
        Assert.False(File.Exists(TransferArtifactPaths.GetCheckpointPath(completed.DestinationPath)));
    }

    [Fact]
    public async Task KeepsTransactionalPartialWhenChecksumDoesNotMatch()
    {
        byte[] payload = CreatePayload(4096, 109);
        using TemporaryDirectory directory = new();
        using HttpClient client = new(new RangeHandler(payload));
        ApplicationState state = new();
        using DownloadManager manager = CreateManager(client, state, new InMemoryHistoryStore());
        string destination = Path.Combine(directory.Path, "mismatch.bin");

        string id = await manager.AddAsync(new DownloadRequest(
            new Uri("https://example.test/mismatch.bin"),
            directory.Path,
            "mismatch.bin",
            ConnectionCount: 1,
            ExpectedChecksumAlgorithm: DownloadChecksumService.Sha256,
            ExpectedChecksum: new string('0', 64)));

        DownloadSnapshot failed = await WaitForStateAsync(state, id, DownloadState.Failed);

        Assert.Equal(DownloadIntegrityStatus.Mismatch, failed.IntegrityStatus);
        Assert.True(failed.RecoveryRequired);
        Assert.True(File.Exists(TransferArtifactPaths.GetPartialPath(destination)));
        Assert.False(File.Exists(destination));
    }

    [Fact]
    public async Task RestoresActualPartialLengthFromDurableCheckpointAfterCrash()
    {
        byte[] payload = CreatePayload(4096, 103);
        using TemporaryDirectory directory = new();
        string destination = Path.Combine(directory.Path, "checkpoint.bin");
        string partialPath = TransferArtifactPaths.GetPartialPath(destination);
        await File.WriteAllBytesAsync(partialPath, payload[..1024]);
        PersistedDownload persisted = new(
            "checkpoint",
            new Uri("https://example.test/checkpoint.bin"),
            destination,
            512,
            payload.Length,
            DownloadState.Downloading,
            DateTimeOffset.UtcNow,
            EntityTag: "\"checkpoint-v1\"");
        ResumeCheckpointStore checkpointStore = new();
        await checkpointStore.SaveAsync(new ResumeCheckpoint(
            ResumeCheckpoint.CurrentVersion,
            persisted.Id,
            persisted.Source,
            destination,
            1024,
            payload.Length,
            persisted.EntityTag,
            null,
            1,
            DateTimeOffset.UtcNow));
        ApplicationState state = new();
        using HttpClient client = new(new RangeHandler(payload, "\"checkpoint-v1\""));
        using DownloadManager manager = CreateManager(client, state, new InMemoryHistoryStore([persisted]));

        await manager.InitializeAsync();

        DownloadSnapshot restored = Assert.Single(state.Current.Downloads);
        Assert.Equal(DownloadState.Paused, restored.State);
        Assert.Equal(1024, restored.DownloadedBytes);
        Assert.Equal(DownloadIntegrityStatus.Checkpointed, restored.IntegrityStatus);
        Assert.Contains("Recovered durable transfer state", restored.RecoveryMessage, StringComparison.Ordinal);
    }

    [Fact]
    public async Task RejectsInterruptedFinalizationWhenLengthDoesNotMatchMarker()
    {
        using TemporaryDirectory directory = new();
        string destination = Path.Combine(directory.Path, "invalid-finalization.bin");
        await File.WriteAllBytesAsync(TransferArtifactPaths.GetPartialPath(destination), new byte[128]);
        await File.WriteAllTextAsync(
            TransferArtifactPaths.GetFinalizationMarkerPath(destination),
            "1024");
        PersistedDownload persisted = new(
            "invalid-finalization",
            new Uri("https://example.test/invalid-finalization.bin"),
            destination,
            128,
            1024,
            DownloadState.Finalizing,
            DateTimeOffset.UtcNow);
        ApplicationState state = new();
        using HttpClient client = new(new RangeHandler(new byte[1024]));
        using DownloadManager manager = CreateManager(client, state, new InMemoryHistoryStore([persisted]));

        await manager.InitializeAsync();

        DownloadSnapshot recovered = Assert.Single(state.Current.Downloads);
        Assert.Equal(DownloadState.Failed, recovered.State);
        Assert.True(recovered.RecoveryRequired);
        Assert.False(File.Exists(destination));
    }

    [Fact]
    public async Task VerificationAndRestartFromZeroPreserveSuspectFileAndDownloadCleanCopy()
    {
        byte[] expectedPayload = CreatePayload(2048, 97);
        byte[] corruptPayload = CreatePayload(2048, 89);
        string expectedChecksum = Convert.ToHexString(SHA256.HashData(expectedPayload));
        using TemporaryDirectory directory = new();
        string destination = Path.Combine(directory.Path, "repair.bin");
        await File.WriteAllBytesAsync(destination, corruptPayload);
        PersistedDownload persisted = new(
            "repair",
            new Uri("https://example.test/repair.bin"),
            destination,
            corruptPayload.Length,
            corruptPayload.Length,
            DownloadState.Completed,
            DateTimeOffset.UtcNow,
            ExpectedChecksumAlgorithm: DownloadChecksumService.Sha256,
            ExpectedChecksum: expectedChecksum);
        ApplicationState state = new();
        using HttpClient client = new(new RangeHandler(expectedPayload));
        using DownloadManager manager = CreateManager(client, state, new InMemoryHistoryStore([persisted]));
        await manager.InitializeAsync();

        DownloadVerificationResult verification = await manager.VerifyAsync("repair");
        Assert.False(verification.IsMatch);

        DownloadRepairResult repair = await manager.RestartFromZeroAsync("repair");
        await WaitForStateAsync(state, "repair", DownloadState.Completed);

        Assert.NotNull(repair.PreservedCorruptPath);
        Assert.True(File.Exists(repair.PreservedCorruptPath));
        Assert.Equal(corruptPayload, await File.ReadAllBytesAsync(repair.PreservedCorruptPath));
        Assert.Equal(expectedPayload, await File.ReadAllBytesAsync(destination));
    }


    [Fact]
    public async Task VerifiesChecksumBeforeCompletingFromRangeAlreadySatisfiedResponse()
    {
        byte[] payload = CreatePayload(2048, 79);
        using TemporaryDirectory directory = new();
        string destination = Path.Combine(directory.Path, "already-complete.bin");
        string partialPath = TransferArtifactPaths.GetPartialPath(destination);
        await File.WriteAllBytesAsync(partialPath, payload);
        PersistedDownload persisted = new(
            "already-complete",
            new Uri("https://example.test/already-complete.bin"),
            destination,
            payload.Length,
            payload.Length,
            DownloadState.Paused,
            DateTimeOffset.UtcNow,
            ExpectedChecksumAlgorithm: DownloadChecksumService.Sha256,
            ExpectedChecksum: new string('0', 64));
        ApplicationState state = new();
        using HttpClient client = new(new RangeAlreadySatisfiedHandler(payload.Length));
        using DownloadManager manager = CreateManager(client, state, new InMemoryHistoryStore([persisted]));

        await manager.InitializeAsync();
        await manager.ResumeAsync(persisted.Id);
        DownloadSnapshot failed = await WaitForStateAsync(state, persisted.Id, DownloadState.Failed);

        Assert.Equal(DownloadIntegrityStatus.Mismatch, failed.IntegrityStatus);
        Assert.True(File.Exists(partialPath));
        Assert.False(File.Exists(destination));
    }

    [Fact]
    public async Task RestoresCheckpointThatWasWrittenAfterMirrorFailover()
    {
        byte[] payload = CreatePayload(2048, 73);
        using TemporaryDirectory directory = new();
        string destination = Path.Combine(directory.Path, "mirror-recovery.bin");
        string partialPath = TransferArtifactPaths.GetPartialPath(destination);
        await File.WriteAllBytesAsync(partialPath, payload[..512]);
        Uri primary = new("https://primary.example.test/mirror-recovery.bin");
        Uri mirror = new("https://mirror.example.test/mirror-recovery.bin");
        PersistedDownload persisted = new(
            "mirror-recovery",
            primary,
            destination,
            256,
            payload.Length,
            DownloadState.Downloading,
            DateTimeOffset.UtcNow,
            Mirrors: [mirror]);
        ResumeCheckpointStore checkpointStore = new();
        await checkpointStore.SaveAsync(new ResumeCheckpoint(
            ResumeCheckpoint.CurrentVersion,
            persisted.Id,
            mirror,
            destination,
            512,
            payload.Length,
            "\"mirror-v1\"",
            null,
            1,
            DateTimeOffset.UtcNow,
            Mirrors: [mirror]));
        ApplicationState state = new();
        using HttpClient client = new(new RangeHandler(payload, "\"mirror-v1\""));
        using DownloadManager manager = CreateManager(client, state, new InMemoryHistoryStore([persisted]));

        await manager.InitializeAsync();

        DownloadSnapshot restored = Assert.Single(state.Current.Downloads);
        Assert.Equal(mirror, restored.Source);
        Assert.Equal(512L, restored.DownloadedBytes);
        Assert.Equal(DownloadState.Paused, restored.State);
        Assert.False(restored.RecoveryRequired);
    }

    [Fact]
    public async Task FailsOverToMirrorAfterPrimaryExhaustsRetries()
    {
        byte[] payload = CreatePayload(1024, 83);
        using TemporaryDirectory directory = new();
        MirrorFailoverHandler handler = new(payload);
        using HttpClient client = new(handler);
        ApplicationState state = new();
        using DownloadManager manager = CreateManager(
            client,
            state,
            new InMemoryHistoryStore(),
            retryPolicy: new DownloadRetryPolicy(1, TimeSpan.FromMilliseconds(1), 0));

        string id = await manager.AddAsync(new DownloadRequest(
            new Uri("https://primary.example.test/file.bin"),
            directory.Path,
            "mirror.bin",
            ConnectionCount: 1,
            Mirrors: [new Uri("https://mirror.example.test/file.bin")]));

        DownloadSnapshot completed = await WaitForStateAsync(state, id, DownloadState.Completed);

        Assert.Equal("mirror.example.test", completed.Source.Host);
        Assert.Equal(1, handler.PrimaryRequests);
        Assert.Equal(1, handler.MirrorRequests);
        Assert.Equal(payload, await File.ReadAllBytesAsync(completed.DestinationPath, CancellationToken.None));
    }

    [Fact]
    public async Task ForcedAria2DownloadIsOwnedAndCompletedThroughUnifiedManager()
    {
        using TemporaryDirectory directory = new();
        byte[] payload = CreatePayload(4096, 197);
        ApplicationState state = new();
        TestSettingsService settings = new(ApplicationSettings.CreateDefault() with
        {
            Aria2 = Aria2IntegrationSettings.Default with { Enabled = true }
        });
        FakeAria2Service aria2 = new(payload);
        using DownloadManager manager = CreateManager(
            new HttpClient(new RangeHandler(payload)),
            state,
            new InMemoryHistoryStore(),
            settingsService: settings,
            aria2Service: aria2);

        string id = await manager.AddAsync(new DownloadRequest(
            new Uri("https://example.test/aria2.bin"),
            directory.Path,
            "aria2.bin",
            BackendPreference: DownloadBackendPreference.Aria2,
            AllowBackendFallback: false));

        DownloadSnapshot completed = await WaitForStateAsync(state, id, DownloadState.Completed);

        Assert.Equal(DownloadBackendKind.Aria2, completed.Backend);
        Assert.Equal("fake-gid", completed.BackendTaskId);
        Assert.Equal(payload, await File.ReadAllBytesAsync(completed.DestinationPath));
        Assert.Equal(1, aria2.AddCount);
    }

    [Fact]
    public async Task Aria2DestinationCollisionBlocksDualOwnership()
    {
        using TemporaryDirectory directory = new();
        byte[] payload = CreatePayload(2048, 113);
        string destination = Path.Combine(directory.Path, "collision.bin");
        ApplicationState state = new();
        TestSettingsService settings = new(ApplicationSettings.CreateDefault() with
        {
            Aria2 = Aria2IntegrationSettings.Default with { Enabled = true }
        });
        FakeAria2Service aria2 = new(payload, destination);
        using DownloadManager manager = CreateManager(
            new HttpClient(new RangeHandler(payload)),
            state,
            new InMemoryHistoryStore(),
            settingsService: settings,
            aria2Service: aria2);

        string id = await manager.AddAsync(new DownloadRequest(
            new Uri("https://example.test/collision.bin"),
            directory.Path,
            "collision.bin",
            ConnectionCount: 1,
            BackendPreference: DownloadBackendPreference.Aria2,
            AllowBackendFallback: true));

        DownloadSnapshot failed = await WaitForStateAsync(state, id, DownloadState.Failed);

        Assert.Equal(DownloadBackendKind.Aria2, failed.Backend);
        Assert.Contains("owns the destination", failed.ErrorMessage, StringComparison.OrdinalIgnoreCase);
        Assert.Equal(0, aria2.AddCount);
        Assert.False(File.Exists(destination));
    }

    [Fact]
    public async Task PersistedAria2OwnershipNeverFallsBackWhileServiceIsUnavailable()
    {
        using TemporaryDirectory directory = new();
        byte[] payload = CreatePayload(1024, 101);
        string destination = Path.Combine(directory.Path, "owned.bin");
        PersistedDownload persisted = new(
            "owned",
            new Uri("https://example.test/owned.bin"),
            destination,
            0,
            payload.Length,
            DownloadState.Paused,
            DateTimeOffset.UtcNow,
            BackendPreference: DownloadBackendPreference.Automatic,
            Backend: DownloadBackendKind.Aria2,
            BackendTaskId: "persisted-gid",
            AllowBackendFallback: true);
        ApplicationState state = new();
        RangeHandler handler = new(payload);
        TestSettingsService settings = new(ApplicationSettings.CreateDefault() with
        {
            Aria2 = Aria2IntegrationSettings.Default with { Enabled = true }
        });
        FakeAria2Service aria2 = new(payload, available: false);
        using DownloadManager manager = CreateManager(
            new HttpClient(handler),
            state,
            new InMemoryHistoryStore([persisted]),
            settingsService: settings,
            aria2Service: aria2);
        await manager.InitializeAsync();

        await manager.ResumeAsync("owned");
        DownloadSnapshot failed = await WaitForStateAsync(state, "owned", DownloadState.Failed);

        Assert.Equal(DownloadBackendKind.Aria2, failed.Backend);
        Assert.Equal("persisted-gid", failed.BackendTaskId);
        Assert.True(failed.RecoveryRequired);
        Assert.Equal(0, handler.RequestCount);
        Assert.False(File.Exists(destination));
    }

    [Fact]
    public async Task FocusesExistingDownloadWhenUrlAlreadyExists()
    {
        byte[] payload = CreatePayload(2048, 71);
        using TemporaryDirectory directory = new();
        using HttpClient client = new(new RangeHandler(payload));
        ApplicationState state = new();
        ApplicationSettings settings = ApplicationSettings.CreateDefault() with
        {
            Organization = OrganizationSettings.Default with
            {
                DuplicateUrlBehavior = DuplicateUrlBehavior.FocusExisting
            }
        };
        using DownloadManager manager = CreateManager(
            client,
            state,
            new InMemoryHistoryStore(),
            settingsService: new TestSettingsService(settings));
        Uri source = new("https://example.test/same.bin");

        string first = await manager.AddAsync(new DownloadRequest(source, directory.Path, "first.bin"));
        string second = await manager.AddAsync(new DownloadRequest(source, directory.Path, "second.bin"));

        Assert.Equal(first, second);
        Assert.Single(state.Current.Downloads);
    }

    [Fact]
    public async Task ConcurrentDuplicateUrlsResolveToOneOwnedDownload()
    {
        byte[] payload = CreatePayload(2048, 73);
        using TemporaryDirectory directory = new();
        using HttpClient client = new(new RangeHandler(payload));
        ApplicationState state = new();
        ApplicationSettings settings = ApplicationSettings.CreateDefault() with
        {
            Organization = OrganizationSettings.Default with
            {
                DuplicateUrlBehavior = DuplicateUrlBehavior.FocusExisting
            }
        };
        using DownloadManager manager = CreateManager(
            client,
            state,
            new InMemoryHistoryStore(),
            settingsService: new TestSettingsService(settings));
        Uri source = new("https://example.test/concurrent.bin");

        Task<string>[] additions = Enumerable.Range(0, 8)
            .Select(index => manager.AddAsync(new DownloadRequest(
                source,
                directory.Path,
                $"concurrent-{index}.bin")))
            .ToArray();
        string[] ids = await Task.WhenAll(additions);

        Assert.Single(ids.Distinct(StringComparer.Ordinal));
        Assert.Single(state.Current.Downloads);
    }

    [Fact]
    public async Task DestinationRuleChangesFolderAndAddsTags()
    {
        byte[] payload = CreatePayload(1024, 67);
        using TemporaryDirectory directory = new();
        string routed = Path.Combine(directory.Path, "routed");
        using HttpClient client = new(new RangeHandler(payload));
        ApplicationState state = new();
        ApplicationSettings settings = ApplicationSettings.CreateDefault() with
        {
            Organization = new OrganizationSettings(
                DuplicateUrlBehavior.Allow,
                false,
                [new DestinationRuleDefinition(
                    "example",
                    "Example files",
                    true,
                    0,
                    routed,
                    HostSuffix: "example.test",
                    Extensions: ["zip"],
                    Tags: ["mirror", "release"])],
                [])
        };
        using DownloadManager manager = CreateManager(
            client,
            state,
            new InMemoryHistoryStore(),
            settingsService: new TestSettingsService(settings));

        string id = await manager.AddAsync(new DownloadRequest(
            new Uri("https://example.test/package.zip"),
            directory.Path,
            "package.zip"));
        DownloadSnapshot completed = await WaitForStateAsync(state, id, DownloadState.Completed);

        Assert.Equal(Path.Combine(routed, "package.zip"), completed.DestinationPath);
        Assert.Collection(
            Assert.IsAssignableFrom<IReadOnlyList<string>>(completed.Tags),
            static tag => Assert.Equal("mirror", tag),
            static tag => Assert.Equal("release", tag));
    }

    [Fact]
    public async Task MarksCompletedFileAsContentDuplicate()
    {
        byte[] payload = CreatePayload(4096, 83);
        using TemporaryDirectory directory = new();
        using HttpClient client = new(new RangeHandler(payload));
        ApplicationState state = new();
        ApplicationSettings settings = ApplicationSettings.CreateDefault() with
        {
            Organization = OrganizationSettings.Default with
            {
                DuplicateUrlBehavior = DuplicateUrlBehavior.Allow,
                ComputeContentHashes = true
            }
        };
        using DownloadManager manager = CreateManager(
            client,
            state,
            new InMemoryHistoryStore(),
            settingsService: new TestSettingsService(settings));

        string firstId = await manager.AddAsync(new DownloadRequest(
            new Uri("https://example.test/first.bin"), directory.Path, "first.bin"));
        await WaitForStateAsync(state, firstId, DownloadState.Completed);
        string secondId = await manager.AddAsync(new DownloadRequest(
            new Uri("https://mirror.test/second.bin"), directory.Path, "second.bin"));
        DownloadSnapshot second = await WaitForStateAsync(state, secondId, DownloadState.Completed);

        Assert.NotNull(second.ContentHashSha256);
        Assert.Equal(firstId, second.DuplicateOfDownloadId);
        Assert.NotNull(second.DuplicateReason);
    }

    [Fact]
    public async Task TagsArchiveAndRelinkArePersisted()
    {
        byte[] payload = CreatePayload(512, 37);
        using TemporaryDirectory directory = new();
        using HttpClient client = new(new RangeHandler(payload));
        ApplicationState state = new();
        InMemoryHistoryStore history = new();
        using DownloadManager manager = CreateManager(client, state, history);
        string id = await manager.AddAsync(new DownloadRequest(
            new Uri("https://example.test/organize.bin"), directory.Path, "organize.bin"));
        await WaitForStateAsync(state, id, DownloadState.Completed);

        await manager.SetTagsAsync(id, ["Work", "work", "Release"]);
        await manager.SetArchivedAsync(id, true);
        string moved = Path.Combine(directory.Path, "moved.bin");
        File.Move(Path.Combine(directory.Path, "organize.bin"), moved);
        await manager.RelinkAsync(id, moved);

        DownloadSnapshot snapshot = Assert.Single(state.Current.Downloads, item => item.Id == id);
        Assert.Collection(
            Assert.IsAssignableFrom<IReadOnlyList<string>>(snapshot.Tags),
            static tag => Assert.Equal("Work", tag),
            static tag => Assert.Equal("Release", tag));
        Assert.True(snapshot.IsArchived);
        Assert.Equal(moved, snapshot.DestinationPath);
        Assert.Contains(history.Downloads, item => item.Id == id && item.IsArchived);
    }

    [Fact]
    public async Task AutomaticallyVerifiesBothConfiguredChecksumsBeforeFinalization()
    {
        byte[] payload = CreatePayload(128 * 1024, 251);
        string sha256 = Convert.ToHexString(SHA256.HashData(payload));
        string sha512 = Convert.ToHexString(SHA512.HashData(payload));
        using TemporaryDirectory directory = new();
        using HttpClient client = new(new RangeHandler(payload));
        ApplicationState state = new();
        using DownloadManager manager = CreateManager(client, state, new InMemoryHistoryStore());

        string id = await manager.AddAsync(new DownloadRequest(
            new Uri("https://example.test/dual-checksum.bin"),
            directory.Path,
            "dual-checksum.bin",
            ConnectionCount: 1,
            ExpectedSha256: sha256,
            ExpectedSha512: sha512), CancellationToken.None);

        DownloadSnapshot completed = await WaitForStateAsync(state, id, DownloadState.Completed);

        Assert.Equal(DownloadIntegrityStatus.Verified, completed.IntegrityStatus);
        Assert.Equal(sha256, completed.ActualSha256);
        Assert.Equal(sha512, completed.ActualSha512);
        Assert.Equal(payload.Length, completed.VerificationBytesProcessed);
    }

    [Fact]
    public async Task RepairReplacesOnlyTheCorruptRangeAndPreservesMatchingData()
    {
        byte[] payload = CreatePayload((5 * 1024 * 1024) + 37, 239);
        string sha256 = Convert.ToHexString(SHA256.HashData(payload));
        using TemporaryDirectory directory = new();
        RangeHandler handler = new(payload);
        using HttpClient client = new(handler);
        ApplicationState state = new();
        using DownloadManager manager = CreateManager(client, state, new InMemoryHistoryStore());

        string id = await manager.AddAsync(new DownloadRequest(
            new Uri("https://example.test/repair.bin"),
            directory.Path,
            "repair.bin",
            ConnectionCount: 1,
            ExpectedSha256: sha256), CancellationToken.None);
        DownloadSnapshot completed = await WaitForStateAsync(state, id, DownloadState.Completed);
        byte[] corrupt = await File.ReadAllBytesAsync(completed.DestinationPath, CancellationToken.None);
        corrupt[(4 * 1024 * 1024) + 12] ^= 0x7f;
        await File.WriteAllBytesAsync(completed.DestinationPath, corrupt, CancellationToken.None);

        DownloadVerificationResult mismatch = await manager.VerifyAsync(id, CancellationToken.None);
        Assert.False(mismatch.IsMatch);

        DownloadRepairResult repaired = await manager.RepairAsync(id, CancellationToken.None);

        Assert.True(repaired.ChecksumMatched);
        Assert.Equal(1, repaired.RepairedRangeCount);
        Assert.Equal(payload.Length - (4L * 1024 * 1024), repaired.BytesDownloaded);
        Assert.Equal(payload.Length - (4L * 1024 * 1024), repaired.BytesRepaired);
        Assert.Equal(payload, await File.ReadAllBytesAsync(completed.DestinationPath, CancellationToken.None));
    }

    [Fact]
    public async Task VerificationWithoutAnExpectedChecksumCreatesALocalRecordWithoutNetworkTraffic()
    {
        byte[] payload = CreatePayload(96 * 1024, 241);
        using TemporaryDirectory directory = new();
        RangeHandler handler = new(payload);
        using HttpClient client = new(handler);
        ApplicationState state = new();
        using DownloadManager manager = CreateManager(client, state, new InMemoryHistoryStore());

        string id = await manager.AddAsync(new DownloadRequest(
            new Uri("https://example.test/local-record.bin"),
            directory.Path,
            "local-record.bin",
            ConnectionCount: 1), CancellationToken.None);
        DownloadSnapshot completed = await WaitForStateAsync(state, id, DownloadState.Completed);
        int requestsBeforeVerification = handler.RequestCount;

        DownloadVerificationResult verified = await manager.VerifyAsync(id, CancellationToken.None);

        Assert.True(verified.IsMatch);
        Assert.True(verified.LocalIntegrityRecordOnly);
        Assert.Equal(DownloadIntegrityStatus.LocalRecord,
            Assert.Single(state.Current.Downloads, item => item.Id == id).IntegrityStatus);
        Assert.Equal(requestsBeforeVerification, handler.RequestCount);
        Assert.Equal(Convert.ToHexString(SHA256.HashData(payload)), completed.ActualSha256);
    }

    [Fact]
    public void ChecksumParserAcceptsCommonSha256AndSha512FileFormats()
    {
        string sha256 = new('A', 64);
        string sha512 = new('B', 128);

        ParsedChecksums parsed = DownloadChecksumParser.Parse(
            $"SHA256(file.bin) = {sha256}{Environment.NewLine}{sha512}  file.bin");

        Assert.Equal(sha256, parsed.Sha256);
        Assert.Equal(sha512, parsed.Sha512);
    }

    private static DownloadManager CreateManager(
        HttpClient client,
        ApplicationState state,
        InMemoryHistoryStore history,
        IDiskSpaceProvider? diskSpaceProvider = null,
        DownloadRetryPolicy? retryPolicy = null,
        ISettingsService? settingsService = null,
        IAria2Service? aria2Service = null)
        => new(
            client,
            state,
            history,
            settingsService ?? new TestSettingsService(),
            NullLogger<DownloadManager>.Instance,
            diskSpaceProvider ?? new FixedDiskSpaceProvider(long.MaxValue),
            retryPolicy ?? new DownloadRetryPolicy(3, TimeSpan.FromMilliseconds(1), 0),
            aria2Service: aria2Service);

    private static async Task<DownloadSnapshot> WaitForStateAsync(
        ApplicationState state,
        string id,
        DownloadState expected)
    {
        using CancellationTokenSource timeout = new(TimeSpan.FromSeconds(10));
        while (!timeout.IsCancellationRequested)
        {
            DownloadSnapshot? snapshot = state.Current.Downloads.FirstOrDefault(item => item.Id == id);
            if (snapshot?.State == expected)
            {
                return snapshot;
            }

            if (snapshot?.State == DownloadState.Failed && expected != DownloadState.Failed)
            {
                throw new InvalidOperationException(snapshot.ErrorMessage);
            }

            await Task.Delay(20, timeout.Token);
        }

        throw new TimeoutException($"Download did not reach {expected}.");
    }

    private static byte[] CreatePayload(int length, int modulus)
        => Enumerable.Range(0, length).Select(value => (byte)(value % modulus)).ToArray();

    private sealed class RangeHandler(byte[] payload, string entityTag = "\"stable\"") : HttpMessageHandler
    {
        public int RequestCount { get; private set; }

        public long? LastRangeStart { get; private set; }

        public string? LastIfRangeEntityTag { get; private set; }

        public string? LastTestHeader { get; private set; }

        public string? LastAuthorizationScheme { get; private set; }

        public string? LastCookie { get; private set; }

        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            RequestCount++;
            LastTestHeader = request.Headers.TryGetValues("X-Test", out IEnumerable<string>? testValues)
                ? testValues.Single()
                : null;
            LastAuthorizationScheme = request.Headers.Authorization?.Scheme;
            LastCookie = request.Headers.TryGetValues("Cookie", out IEnumerable<string>? cookieValues)
                ? cookieValues.Single()
                : null;
            RangeItemHeaderValue? requestedRange = request.Headers.Range?.Ranges.FirstOrDefault();
            LastRangeStart = requestedRange?.From;
            LastIfRangeEntityTag = request.Headers.IfRange?.EntityTag?.ToString();
            int offset = checked((int)(requestedRange?.From ?? 0));
            int end = checked((int)Math.Min(requestedRange?.To ?? (payload.Length - 1), payload.Length - 1));
            ByteArrayContent content = new(payload[offset..(end + 1)]);
            HttpResponseMessage response = new(
                requestedRange is null ? HttpStatusCode.OK : HttpStatusCode.PartialContent)
            {
                Content = content
            };

            response.Headers.ETag = EntityTagHeaderValue.Parse(entityTag);
            response.Content.Headers.ContentLength = end - offset + 1;
            if (requestedRange is not null)
            {
                response.Content.Headers.ContentRange = new ContentRangeHeaderValue(
                    offset,
                    end,
                    payload.Length);
            }

            return Task.FromResult(response);
        }
    }


    private sealed class RangeAlreadySatisfiedHandler(long payloadLength) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            Assert.NotNull(request.Headers.Range);
            ByteArrayContent content = new(Array.Empty<byte>());
            content.Headers.TryAddWithoutValidation("Content-Range", $"bytes */{payloadLength}");
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.RequestedRangeNotSatisfiable)
            {
                Content = content
            });
        }
    }

    private sealed class MirrorFailoverHandler(byte[] payload) : HttpMessageHandler
    {
        public int PrimaryRequests { get; private set; }

        public int MirrorRequests { get; private set; }

        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            if (string.Equals(request.RequestUri?.Host, "primary.example.test", StringComparison.Ordinal))
            {
                PrimaryRequests++;
                throw new HttpRequestException("Primary unavailable");
            }

            MirrorRequests++;
            ByteArrayContent content = new(payload);
            content.Headers.ContentLength = payload.Length;
            HttpResponseMessage response = new(HttpStatusCode.OK) { Content = content };
            response.Headers.ETag = EntityTagHeaderValue.Parse("\"mirror-v1\"");
            return Task.FromResult(response);
        }
    }

    private sealed class PostHandler(byte[] payload) : HttpMessageHandler
    {
        public int RequestCount { get; private set; }
        public HttpMethod? Method { get; private set; }
        public byte[]? Body { get; private set; }
        public string? ContentType { get; private set; }
        public long? RangeStart { get; private set; }

        protected override async Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken)
        {
            RequestCount++;
            Method = request.Method;
            Body = request.Content is null ? null : await request.Content.ReadAsByteArrayAsync(cancellationToken);
            ContentType = request.Content?.Headers.ContentType?.MediaType;
            RangeStart = request.Headers.Range?.Ranges.FirstOrDefault()?.From;
            ByteArrayContent content = new(payload);
            content.Headers.ContentLength = payload.Length;
            return new HttpResponseMessage(HttpStatusCode.OK) { Content = content };
        }
    }

    private sealed class IgnoreRangeHandler(byte[] payload) : HttpMessageHandler
    {
        public long? RequestedRangeStart { get; private set; }

        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            RequestedRangeStart = request.Headers.Range?.Ranges.FirstOrDefault()?.From;
            ByteArrayContent content = new(payload);
            content.Headers.ContentLength = payload.Length;
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK) { Content = content });
        }
    }

    private sealed class TruncatedThenRangeHandler(byte[] payload) : HttpMessageHandler
    {
        public int RequestCount { get; private set; }

        public long? SecondRangeStart { get; private set; }

        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            RequestCount++;
            long offset = request.Headers.Range?.Ranges.FirstOrDefault()?.From ?? 0;
            if (RequestCount == 2)
            {
                SecondRangeStart = offset;
            }

            int start = checked((int)offset);
            byte[] responsePayload = RequestCount == 1
                ? payload[..(payload.Length / 2)]
                : payload[start..];
            ByteArrayContent content = new(responsePayload);
            HttpResponseMessage response = new(
                offset > 0 ? HttpStatusCode.PartialContent : HttpStatusCode.OK)
            {
                Content = content
            };
            response.Headers.ETag = EntityTagHeaderValue.Parse("\"retry-v1\"");
            content.Headers.ContentLength = RequestCount == 1
                ? payload.Length
                : payload.Length - start;
            if (offset > 0)
            {
                content.Headers.ContentRange = new ContentRangeHeaderValue(start, payload.Length - 1, payload.Length);
            }

            return Task.FromResult(response);
        }
    }

    private sealed class FixedDiskSpaceProvider(long availableBytes) : IDiskSpaceProvider
    {
        public long? GetAvailableBytes(string path)
            => availableBytes;
    }

    private sealed class InMemoryHistoryStore : IDownloadHistoryStore
    {
        public InMemoryHistoryStore(IReadOnlyList<PersistedDownload>? downloads = null)
        {
            Downloads = downloads ?? [];
        }

        public IReadOnlyList<PersistedDownload> Downloads { get; private set; }

        public Task<IReadOnlyList<PersistedDownload>> LoadAsync(CancellationToken cancellationToken = default)
            => Task.FromResult(Downloads);

        public Task SaveAsync(
            IReadOnlyCollection<PersistedDownload> downloads,
            CancellationToken cancellationToken = default)
        {
            Downloads = downloads.ToArray();
            return Task.CompletedTask;
        }
    }

    private sealed class FakeAria2Service : IAria2Service
    {
        private readonly byte[] _payload;

        public FakeAria2Service(
            byte[] payload,
            string? occupiedDestination = null,
            bool available = true)
        {
            _payload = payload;
            Current = new Aria2ServiceSnapshot(
                available
                    ? new Aria2Health(true, true, "aria2 test service is ready.", "test")
                    : Aria2Health.Disabled,
                occupiedDestination is null
                    ? []
                    : [CreateTask("occupied-gid", occupiedDestination, Aria2TaskStatus.Active, 0, payload.Length)],
                DateTimeOffset.UtcNow,
                false);
        }

        public int AddCount { get; private set; }

        public Aria2ServiceSnapshot Current { get; private set; }

        public event EventHandler<Aria2ServiceSnapshot>? Changed;

        public Task InitializeAsync(CancellationToken cancellationToken = default) => Task.CompletedTask;

        public Task ConfigureAsync(Aria2IntegrationSettings settings, CancellationToken cancellationToken = default)
            => Task.CompletedTask;

        public Task StartManagedProcessAsync(CancellationToken cancellationToken = default) => Task.CompletedTask;

        public Task StopManagedProcessAsync(CancellationToken cancellationToken = default) => Task.CompletedTask;

        public Task RefreshAsync(CancellationToken cancellationToken = default) => Task.CompletedTask;

        public async Task<string> AddAsync(Aria2AddRequest request, CancellationToken cancellationToken = default)
        {
            AddCount++;
            string path = Path.Combine(request.DestinationDirectory, request.FileName ?? "download.bin");
            await File.WriteAllBytesAsync(path, _payload, cancellationToken);
            Aria2TaskSnapshot task = CreateTask(
                "fake-gid",
                path,
                Aria2TaskStatus.Complete,
                _payload.Length,
                _payload.Length);
            Current = Current with { Tasks = [task], RefreshedAt = DateTimeOffset.UtcNow };
            Changed?.Invoke(this, Current);
            return task.Gid;
        }

        public Task PauseAsync(string gid, CancellationToken cancellationToken = default)
            => SetStatusAsync(gid, Aria2TaskStatus.Paused);

        public Task ResumeAsync(string gid, CancellationToken cancellationToken = default)
            => SetStatusAsync(gid, Aria2TaskStatus.Active);

        public Task RemoveAsync(string gid, CancellationToken cancellationToken = default)
            => SetStatusAsync(gid, Aria2TaskStatus.Removed);

        private Task SetStatusAsync(string gid, Aria2TaskStatus status)
        {
            Aria2TaskSnapshot[] tasks = Current.Tasks
                .Select(task => string.Equals(task.Gid, gid, StringComparison.Ordinal)
                    ? task with { Status = status }
                    : task)
                .ToArray();
            Current = Current with { Tasks = tasks, RefreshedAt = DateTimeOffset.UtcNow };
            Changed?.Invoke(this, Current);
            return Task.CompletedTask;
        }

        private static Aria2TaskSnapshot CreateTask(
            string gid,
            string path,
            Aria2TaskStatus status,
            long completed,
            long total)
            => new(
                gid,
                status,
                Path.GetFileName(path),
                path,
                completed,
                total,
                0,
                0,
                1,
                null,
                null);
    }

    private sealed class TestSettingsService : ISettingsService
    {
        public TestSettingsService(ApplicationSettings? settings = null)
        {
            Current = (settings ?? ApplicationSettings.CreateDefault()).Normalize();
        }

        public ApplicationSettings Current { get; private set; }

        public event EventHandler<ApplicationSettings>? Changed;

        public Task InitializeAsync(CancellationToken cancellationToken = default)
            => Task.CompletedTask;

        public Task UpdateAsync(ApplicationSettings settings, CancellationToken cancellationToken = default)
        {
            Current = settings.Normalize();
            Changed?.Invoke(this, Current);
            return Task.CompletedTask;
        }
    }

    private sealed class TemporaryDirectory : IDisposable
    {
        public TemporaryDirectory()
        {
            Path = System.IO.Path.Combine(System.IO.Path.GetTempPath(), $"xdm-tests-{Guid.NewGuid():N}");
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
