# XDM atomic finalization recovery and shutdown tracking

This overlay adds staged finalization recovery and durable application-session tracking on top of the validated verify/repair and checksum workflow.

## Finalization recovery

- Persists a versioned finalization journal through prepared, promotion, destination-ready, destination-committed, and metadata-committed stages.
- Keeps same-filesystem completion atomic through rename.
- Uses a destination-local, write-through staging file when a direct move crosses filesystems.
- Validates destination length and checksum before committing recovered files.
- Preserves conflicting destination files as `.stale-finalization` artifacts.
- Converts invalid or truncated recovery candidates into a paused/recovery-required failure instead of aborting application startup.
- Persists content-hash and duplicate metadata before the first observable completed snapshot.

## Shutdown tracking

- Persists a session UUID, startup/shutdown timestamps, active download IDs, checkpoint flush counts, and failed checkpoint IDs.
- Marks shutdown clean only after active operations drain and durable checkpoints are flushed.
- Leaves the session unclean when draining or checkpoint persistence fails.
- Feeds previous active-transfer state into startup recovery and support bundles.

## Performance continuity

- Keeps large-history updates indexed while replacing managed per-item copies and full dictionary rebuilds with `Array.Copy` and incremental index updates.
- Preserves immutable application snapshots while avoiding the ARM64 chroot performance regression covered by `LargeHistoryPerformanceTests`.

## Validation

The artifact requires restore, build, tests, and zero warnings. It includes regression coverage for interrupted finalization, cross-filesystem promotion, shutdown checkpoint flushing, clean/unclean session tracking, content-duplicate publication, and large-history update performance.
