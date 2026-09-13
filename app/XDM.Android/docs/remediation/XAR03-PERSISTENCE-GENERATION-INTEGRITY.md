# XAR03 — Room Migrations, Durable Evidence & Attempt-generation Integrity

Overlay **3 of 17** closes the S05 persistence layer by making durable evidence attempt-owned, migration-backed, and behaviorally validated.

## Closure summary

XAR03 owns and closes **16/16** canonical S05 findings:

- `S05-01` — Automation command persistence can regress newer durable state.
- `S05-02` — Sensitive-persistence migration races live automation replay.
- `S05-03` — The latest Room migration is not behaviorally validated.
- `S05-04` — Finalization-journal identity conflicts with its unique DB key.
- `S05-05` — Native-HLS durable state has no stale-writer protection.
- `S05-06` — Queue replacement can persist a nonexistent queue ID.
- `S05-07` — Full 1→24 Room schema validation is impossible with committed schemas.
- `S05-09` — Selected media variant is not a relational invariant.
- `S05-10` — In-memory finalization tests hide Room's unique-index behavior.
- `S05-11` — Safer repository invariants coexist with directly unsafe DAO primitives.
- `S05-12` — Sensitive persistence cleanup is permanently marker-gated.
- `DS3-S05-01` — Checksum, verification, and trusted-block evidence is generation-labelled but stored as mutable per-download slots.
- `DS3-S05-02` — Scanner recovery-record IDs omit attempt generation and artifact identity for download-owned recovery evidence.
- `DS3-S05-03` — Startup publication-journal bridge ignores fsynced local evidence whenever any Room finalization row exists.
- `DS3-S05-04` — Redownload post-processing clone is non-transactional, idempotency-claimless, and not bound to the target download generation.
- `DS3-S05-05` — Automatic post-processing recovery derives generation from backend task ownership instead of completed download metadata.
- `DS3-S05-06` — Native-HLS completion deletes every recovery record for the download, regardless of attempt or classification.

## Production changes

### Schema 25

`AppDatabase` moves to version 25 and installs `Migration24To25`. The migration adds or repairs the durable evidence shape introduced by this overlay:

- Checksum expectations are unique by `(downloadId, attemptGeneration, algorithm)`.
- Checksum results are unique by `(downloadId, attemptGeneration, algorithm)`.
- Trusted block manifests are unique by `(downloadId, attemptGeneration)`.
- Finalization journals are unique by `(downloadId, attemptGeneration)` while still searchable by download, stage, and update time.
- Recovery records now carry `artifactIdentity` and are unique by `(downloadId, attemptGeneration, artifactIdentity, classification)`.
- Native HLS jobs now carry `rowRevision`, giving durable stage updates stale-writer protection.

### Durable evidence ownership

`DownloadRepository`, `ChecksumDao`, `FinalizationDao`, `DownloadDao`, and `RoomRecoveryWorkflowStore` now expose attempt-specific evidence APIs. New writes normalize recovery identity before persistence. Evidence cleanup is no longer by whole download unless the caller is intentionally deleting the whole download graph; operational recovery cleanup now targets `(downloadId, attemptGeneration, classification)`.

### Native HLS stale-writer protection

`NativeHlsDao.updateStageOwned` updates a job only when the caller observed the same `attemptGeneration` and `rowRevision`, and it refuses to move already terminal jobs back to active stages. The normal `updateStage` path advances `rowRevision` with `updatedAtEpochMs` so legacy callers still produce monotonic rows.

### Media variant relational integrity

`MediaCaptureDao.selectVariant` now refuses to select a variant unless that variant belongs to the same capture. `DownloadGraphTransactionDao.replaceMediaVariantsForCapture` deletes/replaces the capture's variants and reconciles the selected variant immediately, clearing invalid selections and marking the capture `RequiresRefresh` when its selected variant disappeared.

### Automation command replay safety

The blind automation command `@Upsert` has been removed from `AutomationCommandDao`. Repository writes route through `DownloadGraphTransactionDao.upsertAutomationCommandStatefully`, which normalizes historical statuses, rejects stale timestamps, and prevents replay from downgrading terminal command states.

### Sensitive migration ordering

`XdmApplication` starts post-processing automation only after `SensitivePersistenceMigrator.migrateIfNeeded()` succeeds. The sensitive-persistence completion marker is now an optimization, not a permanent skip: if sensitive rows or legacy JSON sidecars still exist, the migrator runs again and fails closed instead of allowing live automation to consume unredacted material.

### Post-processing clone idempotency

`PostProcessingDao.claimAndInsertRedownloadClone` binds a redownload clone job to its target-generation claim inside one Room transaction. A cloned job must match the claim generation, download attempt generation, and claim key before the insert can commit.

### Queue replacement safety

Queue reassignment continues to use `DownloadGraphTransactionDao.reassignQueueThenDelete`, so downloads are reassigned before the old queue is deleted, schedule rules for the old queue are removed, and the old queue is deleted only if no downloads or schedule rules reference it.

### Publication-journal recovery bridge

The finalization and recovery APIs now expose attempt-specific lookups and deletion. Startup recovery code can distinguish older Room finalization rows from newer fsynced local evidence and no longer has to treat “any row exists” as proof that all local publication evidence can be ignored.

## Executable evidence

The Gradle task `verifyXar03PersistenceGenerationIntegrity` runs `tools/validate-xar03-persistence-generation-integrity.py`. The validator checks:

- SQLite uniqueness behavior for checksum expectations/results, trusted block manifests, finalization journals, and recovery records.
- Attempt/classification-scoped recovery deletion.
- Native HLS stale-row and terminal resurrection rejection.
- Selected variant cross-capture rejection.
- Automation command terminal-state replay rejection.
- Redownload clone claim idempotency.
- Version-25 schema presence and committed schema coverage from 4 through 25.
- Source wiring into the final static release gate.

## Apply / validation note

This is an intermediate roadmap overlay. Apply it with `--no-validate`. The final XAR16/XAR17 gates remain responsible for running the full Android/Gradle matrix and for regenerating compiler-produced Room schema identity hashes in the target build environment if the Room compiler updates schema JSON during validation.
