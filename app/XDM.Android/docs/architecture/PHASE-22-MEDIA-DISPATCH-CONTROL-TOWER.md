# Phase 22: Media Dispatch Control Tower

Phase 22 turns the Phase 21 execution-hardening model into a review-first dispatch runbook. It does not add a top-level route, does not migrate Room, and does not start background work directly from the planner. The Media route remains the single cockpit for browser/share handoff media.

## Goals

- Convert each queued media spec and engine plan into a dispatch plan before execution.
- Gate dispatch on resolver readiness, metadata freshness, Termux availability, protected-media state, and secret-redaction safety.
- Show a dashboard that summarizes ready, refresh-required, Termux-required, blocked, and secret-safe media jobs.
- Attach retry policy, progress signals, terminal cleanup, and redacted diagnostics to every media dispatch path.
- Keep yt-dlp/aria2/native execution typed and reviewable without raw shell exposure.

## Dispatch lanes

The dispatch planner consumes `MediaExecutionEnginePlan` lanes introduced in Phase 21:

- `DirectNative`
- `Aria2Segmented`
- `YtDlpAdaptive`
- `LiveRecording`
- `ProtectedBlocked`

It then computes `MediaDispatchReadiness`:

- `Ready`
- `AwaitingUserChoice`
- `NeedsMetadataRefresh`
- `NeedsTermuxSetup`
- `BlockedProtected`
- `BlockedSecretLeak`

Only `Ready` enables queue dispatch. Protected media remains diagnostic-only and no DRM bypass is attempted.

## Runbook steps

Each dispatch plan is a safe runbook composed of typed steps:

- preflight resolver state
- prepare transient cookie/aria2 files when needed
- queue Android background work or launch typed Termux job
- persist redacted offline-library sidecar
- register terminal cleanup
- verify no durable secrets
- notify the user through visible progress signals

The runbook is pure Kotlin. It does not write files, call Termux, or start WorkManager/UIDT jobs by itself. That keeps the contract testable without binding to Android runtime side effects.

## Secret handling

Dispatch diagnostics are intentionally lossy. The planner records counts, labels, hosts, typed argument counts, and redacted URLs, but not cookie values, Authorization values, bearer tokens, signed query strings, or raw shell commands. Temporary Netscape cookie files and aria2 input/session files remain cleanup-owned artifacts from Phase 21.

## UI

The existing Media route now exposes:

- `Media dispatch control tower` dashboard across captures.
- Per-capture `Dispatch runbook` card.
- Readiness pills, primary action labels, retry counts, progress signals, and warnings.

No new top-level routes are added.

## Validation policy

Per project direction, intermediate phases after Phase 21 are applied with `--no-validate`. This phase still ships a static validator and architecture contract so the final release gate can replay checks in one pass.
