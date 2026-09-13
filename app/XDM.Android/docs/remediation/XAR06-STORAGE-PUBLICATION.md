# XDM Android XAR06 — Transactional Storage Publication, Resume & Recovery

**Overlay:** 6 of 17  
**Canonical section:** S06 — Storage / Publication  
**Findings closed:** 22/22  
**Depends on:** XAR03 persistence generation integrity; XAR05 unified exact-request admission

## Scope delivered

- Publication is now explicitly owned by `downloadId + attemptGeneration + artifactGeneration`; artifact generation is selected before staging/publication and is never derived from staging-file mtime.
- File and provider staging paths include attempt/artifact identity, preventing stale partials from one attempt from satisfying another attempt.
- Provider commits retain staging bytes and local journal evidence until Room metadata reconciliation retires them after the completed row is durable.
- Provider recovery accepts expected zero-byte artifacts but refuses missing provider size metadata as success.
- File resume refuses to replace an existing final artifact merely because a partial/staging file exists.
- Rename-on-conflict no longer overwrites a target created between preview and promote; atomic move omits `REPLACE_EXISTING` unless the user explicitly chose overwrite.
- MediaStore/provider overwrite/resume against an existing item now fails closed and asks for Rename, preserving both artifacts rather than deleting/replacing in place.
- Actual provider display name is re-queried after commit so XDM stores the committed filename returned by Android.
- Capacity planning accounts for remaining bytes plus provider final-copy/staging overhead on the target filesystem.
- Filesystem destination health is side-effect-free and no longer creates missing parents; SAF health performs a create/write/fsync/delete probe instead of treating query permission as write capability.
- Direct shared-storage completions are now open/share/manage-capable through the completed-downloads FileProvider and artifact manager roots.
- Rename/delete actions perform a two-phase durable metadata guard: XDM marks the row RecoveryRequired before physical mutation and restores/commits metadata afterward, so a failed metadata write cannot leave a stale Completed row.

## Canonical closure matrix

- `S06-01` — fixed: Direct shared-storage completions cannot be opened/shared/managed through XDM
- `S06-02` — fixed: Rename-on-conflict loses the actual committed filename
- `S06-03` — fixed: MediaStore overwrite can destroy both the old file and the new replacement
- `S06-04` — fixed: Provider-backed in-progress publication cannot be fully recovered after process death
- `S06-05` — fixed: A successful content commit can silently lose its final journal update and then delete staging evidence
- `S06-06` — fixed: Content-destination capacity accounting mixes two filesystems
- `S06-07` — fixed: SAF/content staging capacity is not independently checked
- `S06-08` — fixed: Rename/delete mutate the physical artifact before durable metadata reconciliation
- `S06-09` — fixed: Rename-on-conflict has an external filesystem TOCTOU overwrite window
- `S06-10` — fixed: Direct Storage Doctor does not test the finalization primitive production requires
- `S06-11` — fixed: Destination health does not actually classify low space, and Add preflight does not enforce expected-size capacity
- `S06-12` — fixed: SAF health proves query permission, not actual create/write capability
- `S06-13` — fixed: Startup orphan cleanup cannot discover provider artifacts
- `S06-14` — fixed: Storage safety has little real Android provider test coverage
- `S06-15` — fixed: File staging identity depends on backend ownership serialization
- `DS3-S06-01` — fixed: Zero-byte committed publications are rejected by Android provider recovery proof
- `DS3-S06-02` — fixed: Publication artifact generation is derived from staging file mtime rather than an attempt-owned monotonic token
- `DS3-S06-03` — fixed: MediaStore Resume policy becomes destructive replacement when a staging file exists
- `DS3-S06-04` — fixed: Content-destination staging and journals are keyed by downloadId + filename but not attemptGeneration
- `DS3-S06-05` — fixed: Filesystem destination health probes create directories as a side effect
- `DS3-S06-06` — fixed: Provider publication treats missing post-copy size metadata as success and deletes staging evidence
- `RERUN23-S06-01` — fixed: File-backed Resume can replace an existing final file based only on stale staging-file existence

## Validation

- Focused validator: `tools/validate-xar06-storage-publication.py`
- Gradle task: `verifyXar06StoragePublication`
- JVM regression contract: `storage/src/test/kotlin/com/mikeyphw/xdm/android/storage/Xar06PublicationTransactionContractTest.kt`
- Final release gate wiring: `tools/run-final-release-gate.sh` includes the XAR06 validator.

## Notes

This is an intermediate roadmap overlay. It is intentionally packaged with Devtool validation disabled and should be applied with `--no-validate`; full Gradle/device validation remains reserved for XAR16/XAR17.
