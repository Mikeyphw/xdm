# Atomic finalization recovery and shutdown tracking

## Finalization journal

Completed transfers use `destination.xdm.finalizing` as a versioned operation journal. The journal records:

- expected length and verified checksum;
- local-integrity-only status;
- source partial and destination staging paths;
- the current finalization stage;
- creation and last-update timestamps.

Stages are `Prepared`, `PromotionStarted`, `CopyingToDestination`, `DestinationReady`, `DestinationCommitted`, and `MetadataCommitted`.

A normal same-directory completion still uses an atomic rename. If the source and destination cannot be renamed directly, XDM copies into `destination.xdm.promoting`, flushes it to stable storage, validates its length and checksum, and then atomically renames that destination-local staging file.

Startup recovery validates the final destination first, then a complete staging file, then the original partial. It can therefore recover crashes:

- after the final byte but before promotion;
- after destination promotion but before history persistence;
- after checksum verification but before the completed state is persisted;
- during a cross-filesystem copy;
- after a destination-local staging copy but before its final rename.

A valid destination wins over duplicate partial data. Invalid destination conflicts are preserved as stale finalization artifacts before a verified partial is promoted.

## Shutdown session state

`session.running.json` is an atomic, durable session record rather than a presence-only marker. It records:

- session ID, process ID, startup time, and safe-mode state;
- shutdown start time;
- downloads active when shutdown began;
- checkpoint flush attempt and completion times;
- attempted/written checkpoint counts;
- failed download IDs;
- clean-shutdown completion time.

The desktop shutdown sequence is:

1. Capture active transfer IDs and persist shutdown-started state.
2. Pause/cancel active transfer operations.
3. Force durable checkpoints and persist paused transfer state.
4. Persist the checkpoint flush result.
5. Mark the session clean only when the flush succeeded.
6. Archive the clean record to `session.last.json` and remove `session.running.json`.

A crash at any earlier step leaves the running record intact. Startup recovery uses both that state and transfer artifacts, so unexpected termination is not inferred only from file presence.
