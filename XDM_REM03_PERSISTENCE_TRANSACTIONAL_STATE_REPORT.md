# XDM Desktop REM03 — Persistence, history, schema, import/export, and transactional state

## Scope

REM03 closes the 19 findings assigned by the frozen S00–S16 remediation ledger: **4 High, 11 Medium, 4 Low**. It depends on REM01 and REM02.

## Durable-state foundation

- Added `AtomicFile`, a shared unique-temp + flush-to-disk + backup + atomic-replace primitive. Temporary files are removed in `finally` and corrupt/incompatible state can be quarantined instead of overwritten.
- Settings now recover from a valid backup after ordinary corruption/I/O failure. A future settings schema is quarantined and rejected rather than normalized into the current schema or silently replaced by an older backup.
- Download history now uses a versioned envelope while retaining one-way compatibility with legacy bare arrays. Unsupported future history is rejected/quarantined before rewrite.
- History restore validates unique download IDs and unique normalized destination ownership before sessions are reconstructed, preventing concurrent writers for one destination.
- Recovery metadata, scheduler state, productivity state, and window placement use the atomic writer. Recovery metadata I/O is no longer a hard startup dependency.
- Window placement and recovery now resolve the same state directory/file ownership convention.

## Transactional download/history mutations

- Delete and prune save the retained history while holding the persistence gate before removing live sessions or deleting transfer artifacts.
- Relocate performs durable history persistence before treating the move as committed; failures restore the session and attempt to move the file/repair state back to the previous location.
- Priority, source refresh, tags, archive state, relink, queue moves, and undo restore previous in-memory state when durable history persistence fails.
- History retention is applied on every ordinary persistence cycle rather than only during startup/manual pruning; expired terminal entries are removed from live application state after the retained snapshot commits.

## Import/export correctness and privacy

- Credential-free download-list export removes URI userinfo, query strings, and fragments from source, source-page, and mirror URLs before serialization.
- Redacted settings import honors the export's `includesSecrets=false` intent and preserves existing proxy, host credential, and aria2 secrets from the current baseline.
- Java `.properties` migration now handles logical-line continuation, whitespace/`=`/`:` separators, backslash escapes, and `\\uXXXX` escapes.
- Mirror URI deduplication is no longer case-insensitive across the complete absolute URI.
- Invalid `DownloadPriority` values normalize safely to `Normal` just as backend preference already does.
- Settings import loads the candidate into the ViewModel for review and requires the existing Save action before persistence.
- Legacy migration counts now report data actually parsed from the source rather than counting preserved baseline categories/queues as imported.

## Finding ownership

| Finding | Resolution |
| --- | --- |
| S12-01 HIGH | Delete/prune history mutations persist retained state before destructive live/file changes. |
| S12-02 HIGH | Relocate rolls file/session state back if durable history commit fails. |
| S12-03 HIGH | History load rejects duplicate destination ownership. |
| S12-04 HIGH | Credential-free download-list export strips userinfo/query/fragment. |
| S01-10 MEDIUM | Recovery metadata I/O is contained and backup-aware instead of startup-fatal. |
| S12-05 MEDIUM | Settings backup participates in real recovery. |
| S12-06 MEDIUM | History backup participates in JSON/I/O/permission recovery. |
| S12-07 MEDIUM | Retention is continuously enforced during persistence. |
| S12-08 MEDIUM | Future settings schema is rejected/quarantined, never downgraded. |
| S12-09 MEDIUM | History has a format/schema envelope with legacy-array migration. |
| S12-10 MEDIUM | Redacted settings imports preserve current secrets. |
| S12-11 MEDIUM | Java Properties continuation/separator/escape semantics implemented. |
| S12-12 MEDIUM | Mirror dedupe uses URI identity without whole-URI case folding. |
| S12-13 MEDIUM | Invalid priority is validated/normalized on import. |
| S12-14 MEDIUM | Metadata mutations roll back on persistence failure. |
| S01-14 LOW | Recovery/window-state file ownership convention unified. |
| S12-15 LOW | Imported settings remain review-only until Save. |
| S12-16 LOW | Shared atomic writer gives deterministic unique-temp cleanup. |
| S12-17 LOW | Legacy import counts only source-imported items. |

## Regression coverage

Added/expanded tests cover:

- future-schema rejection and corrupt-settings backup recovery;
- duplicate destination rejection during history load;
- redacted settings secret preservation;
- Java Properties continuation and escape handling;
- credential-free URI export and invalid-priority normalization;
- metadata rollback on history-store failure;
- delete preservation on failed durable history mutation;
- relocate file/path rollback on failed durable history mutation.

## Devtool/Git policy

This overlay contains a schema-v2 `.devtool-artifact.json` with `apply.commit.enabled=true` and a single-commit message. It also requires a clean worktree so the uncommitted REM01/REM02 catch-up cannot be accidentally absorbed into REM03's artifact-scoped commit.

REM03 remains an intermediate campaign overlay, so validation is intentionally not required by the artifact manifest; the campaign's comprehensive validation is reserved for the final seal. Targeted compilation/tests should still be used when debugging an apply failure.
