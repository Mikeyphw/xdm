# XDM Android RM02 Implementation Report

**Roadmap:** Overlay 2 of 4 — Persistence + Downloads UX  
**Date:** 2026-09-16  
**Base:** RM01 Transfer Core / Recovery Integrity applied

## Implemented

### Media capture / Download integrity
- Kept media-capture Download creation transactional.
- Hardened legacy media-link mutation so it cannot point at a missing Download row.
- Added startup repair for stale `DownloadCreated` links and orphan app-owned media outputs.
- Made Download graph deletion reconcile media outputs/captures before the Download row is removed.
- Rebinds a capture to another live app output when available; otherwise returns it to `MetadataReady`.
- Corrected the Debug Center transaction probe to validate the `Cancelled` test row it creates instead of falsely expecting `Queued`.

### Human-readable download identity
- Added `DownloadPresentationPolicy` for user-facing naming and lifecycle semantics.
- UUID/hash-like internal identifiers are no longer preferred card identities.
- Fallback uses reviewed filename, usable URL path filename, page title, then a host-based `download-<host>` name with known extension.
- Existing intake preflight remains responsible for server `Content-Disposition` filename suggestions.

### Downloads card UX
- Title receives the main content width instead of competing with multiple actions.
- Status/source metadata moved below the title.
- Card renders one explicit text-labeled state-aware quick action plus overflow.
- Finalization failure is shown as `Needs attention` / `Finalization failed` with `Transfer complete` semantics.
- The misleading completed payload progress bar is removed for post-transfer finalization failures.
- Raw attempt-generation exception is kept out of primary card copy.
- Destination copy distinguishes `Saved to …` from `Destination: …`.
- The malformed size/progress composition path that produced `MiBF` is removed by state-aware byte formatting.

### Recovery actions + notifications
- Final-save recovery action is `Retry finalization`.
- Historical positive-attempt-generation failures are treated as publication/finalization recovery, not a normal network retry.
- Finalization notifications use `Couldn't finish download` and a friendly transfer-complete explanation.
- Terminal notification records replay their persisted action set after restart, avoiding action drift.

## Regression coverage
- Download presentation policy tests for UUID fallback and finalization classification.
- Download UI truth tests for transfer-complete finalization failure and suppression of misleading progress.
- Download action planner tests for phase-specific retry/recovery behavior.
- Notification policy contract for friendly copy and `Retry finalization`.
- RM02 cross-module persistence/Downloads UX contract.
- Static RM02 release validator wired into Gradle and the final release gate.

## Validation policy
RM02 is an intermediate overlay. Targeted static validation is run for this artifact. Full Gradle/device/release validation remains assigned to Overlay 4 of 4 per the roadmap.

## Next
**Overlay 3 of 4 — Embedded Runtimes + HLS Networking.**
