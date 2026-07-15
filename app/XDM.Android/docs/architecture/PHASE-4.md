# Phase 4 — Android execution and notification layer

Phase 4 connects the native transfer backend to Android lifecycle primitives.

## Execution policy

- Android 14 and newer: direct user actions schedule a `JobScheduler` user-initiated data-transfer job.
- Android 8 through 13: direct user actions use a `dataSync` foreground service.
- Notification actions may start the foreground service because they are explicit user interactions.
- Reboot and package replacement enqueue a WorkManager restoration worker. They never launch a data-sync foreground service from `BOOT_COMPLETED`.

## Runtime ownership

`TransferExecutionRuntime` is the only component that starts backend tasks. It:

- loads the durable Room download record;
- releases stale in-memory ownership after process death;
- claims the destination transactionally;
- starts the selected backend;
- mirrors backend snapshots into Room;
- aggregates active bytes, speed, and progress;
- emits terminal events for completion/failure notifications;
- pauses interrupted states on process recreation or reboot.

The runtime depends on `TransferDownloadStore`, not Room directly. `RepositoryTransferDownloadStore` is the production adapter and JVM tests use an in-memory store.

## Notifications

The active channel exposes:

- aggregate count, speed, and progress;
- pause-all and resume-all actions;
- cancel for the primary transfer;
- a content intent back to XDM.

The status channel reports completed, failed, and restored transfers.

## Recovery semantics

A process start or reboot converts `Connecting`, `Downloading`, `Verifying`, `Repairing`, and `Finalizing` records to `Paused`. Native `.xdm.part` and checkpoint artifacts are preserved. `Queued` records are not mistaken for interrupted work.

## Current boundary

Phase 4 uses app-owned `file:` destinations. MediaStore and Storage Access Framework destinations arrive in Phase 5. Embedded aria2 remains a capability placeholder until Phase 6.
