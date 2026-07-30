# Phase 51 Recovery + Storage Doctor

Phase 51 adds a safe recovery cockpit for the operational states exposed by Phase 50: missing partial files, orphaned app-private artifacts, interrupted finalization, and completed files that still need a visibility check.

## Scope

- Activity → Recovery gains a Recovery + Storage Doctor summary card.
- Recovery records are grouped into resumable, missing partial, untracked artifact, completed visibility, and interrupted finalization buckets.
- Users can validate all linked records safely through the existing recovery validation path.
- Users can copy a recovery report for support without leaking raw paths, URLs, cookies, tokens, signatures, or Authorization values.
- Per-record expanded details use human artifact labels instead of raw file paths or download IDs.

## Safety boundary

- No automatic deletion.
- No automatic orphan adoption.
- No raw paths in normal UI.
- No raw download IDs in normal UI.
- No source URLs or secrets in the recovery report.
- No Room migration; schema remains 14.
- No new top-level route.
- No Debug Workbench D8 or reopening of the sealed D-series.

## Follow-up

Real file deletion can be added later only behind an ownership-proof contract that proves the artifact is app-private, unlinked, and intentionally selected by the user.
