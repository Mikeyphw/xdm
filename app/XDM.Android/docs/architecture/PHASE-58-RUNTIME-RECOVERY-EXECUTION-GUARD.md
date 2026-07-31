# Phase 58 — Runtime Recovery Execution Guard

Phase58 sits between the Phase57 recovery planner and the existing retry/method/recovery callbacks.
It makes each recovery action explicit about whether it can run now, needs Recovery Doctor first,
only provides guidance, or only copies a redacted report.

## User-facing behavior

- Download details show an **Action safety** summary in the recovery options card.
- Partial or recovery-required downloads route retry/method-switch actions through Recovery Doctor first.
- Captured-session retry is review-first so stale cookies, expiring links, and sign-in context are not blindly reused.
- Browser refresh and yt-dlp guidance do not start background work; they tell the user which safe flow to open next.
- Reports remain redacted and copy-only.

## Boundaries

Phase58 does not auto-start retries, does not delete files, does not persist browser headers, does not add all-files
permission, does not add a top-level route, does not reopen Debug Workbench, and does not change Room schema 14.
