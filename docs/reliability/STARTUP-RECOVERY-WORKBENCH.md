# Startup recovery coordinator and workbench

XDM performs a bounded recovery scan after the download manager restores persisted history and before scheduler/browser startup completes.

## Coordinator responsibilities

The coordinator combines persisted download records, current engine snapshots, durable resume checkpoints, transactional partial files, segment directories, and finalization markers. It classifies candidates as:

- ready to resume;
- requiring remote validation;
- requiring repair;
- missing partial data;
- changed remote file;
- completion recovered from an interrupted finalization window;
- orphaned transfer artifact.

The scan includes the configured default/category/rule destination directories plus directories referenced by download history. Recursive artifact enumeration is capped at 4,096 files per scan. The coordinator does not start downloads.

## Safe startup behavior

The download manager already restores transfers that were connecting, downloading, or finalizing as paused. Recovered partial or segmented state also remains paused. The coordinator presents those jobs for review instead of bypassing normal resume validation.

## Validation

The workbench can perform a 15-second bounded identity probe. It prefers `HEAD` and falls back to a `GET` request for byte `0-0` when the server rejects `HEAD`. It compares:

- total length;
- `ETag`;
- `Last-Modified`;
- byte-range behavior.

A mismatch is classified as a changed remote file and normal resume remains unavailable from the workbench.

## Recovery screen

The dedicated Recovery page is selected automatically after an unclean shutdown. It displays local byte counts, expected length, checkpoint time, validators, expected checksum, recommended action, and the reason a normal resume may be unsafe.

Available actions are resume, validate, verify/repair, restart from zero, locate the partial file, open its folder, and remove the recovery record without deleting artifacts.
