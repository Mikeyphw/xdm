# Bug Hunt Remediation Phase 6: Database Integrity And Migrations

Phase 6 turns database cleanup and command handling into explicit transactions instead of hoping Room cascades and whole-row upserts paper over graph state.

## Guarantees

- Download history deletion now routes through `DownloadGraphTransactionDao.deleteDownloadGraph(...)`, which deletes every known download-linked table in one Room transaction.
- Queue deletion uses `deleteQueueIfUnreferenced(...)` and the Phase 4 safe-delete planner instead of blindly deleting queues while downloads or schedules still reference them.
- Download progress/state writers get a compare-and-swap DAO entry point through `updateDownloadCompareAndSwap(...)` so runtime owners can avoid overwriting a newer state.
- Media variants can be replaced transactionally by capture ID; obsolete variants are removed, `variantCount` is recomputed, and disappeared selections are invalidated as `RequiresRefresh`.
- Automation command persistence now accepts durable states: `Received`, `Claimed`, `Executing`, `Applied`, `Failed`, while legacy `Accepted`/`Executed` are mapped forward for compatibility.
- The 5-to-6 migration now preserves legacy aria2 mappings as `RecoveryRequired` rows with `LEGACY_SCHEMA` evidence instead of dropping the table payload silently.
- Migration/schema tests now explicitly check Phase 6 database contracts and require schema exports through version 14.
- Portable settings get a structured encoding policy in `DatabaseIntegrityPolicy` so future settings migrations stop relying on delimiter-soup parsing.

## Validation mode

This is an intermediate apply-only overlay. `.devtool-artifact.json` has `validation.mode = disabled`; final Gradle validation should run at the final overlay.


## Phase 6 r2 runtime wiring correction

Phase 6 r2 fixes the r1 audit gaps: repository download saves now use a stale-write guarded insert/update transaction, automation commands are claimed and moved into Executing before MainViewModel side effects run, queue reassignment deletion uses the transactional repository helper, and stored enum parsing fails closed instead of throwing from Room row conversion. The validator now rejects marker-only implementations.

Phase 6 r2 also hardens backend ownership/migration store enum parsing so malformed persisted backend rows become safe recovery/default records instead of process-fatal `valueOf` crashes.
