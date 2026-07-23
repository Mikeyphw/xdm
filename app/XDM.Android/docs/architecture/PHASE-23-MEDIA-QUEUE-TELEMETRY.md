# Phase 23: Media Queue Telemetry

Phase 23 turns the Phase 22 dispatch runbook into an observable queue deck inside the existing Media route.

## Goals

- Merge `MediaDispatchPlan` readiness with `MediaExecutionJob` state.
- Show ready, active, attention, cleanup, terminal, and redaction-health counts.
- Show per-capture progress pulse, next action, cleanup state, and safe diagnostics.
- Keep the implementation pure Kotlin until final validation.
- Avoid Room schema changes, Android service changes, raw shell rendering, or new top-level routes.

## Data model

`MediaQueueTelemetryPlanner` accepts dispatch plans and execution jobs and emits a `MediaQueueTelemetryDeck`.

Each `MediaQueueTelemetryRow` includes:

- capture id and title
- execution lane
- dispatch readiness
- execution stage or `Not queued`
- progress label
- next action label
- terminal cleanup state
- stalled state
- secret-safe state
- redacted diagnostic text

## Safety

The telemetry deck is intentionally diagnostic-only. It never stores or displays raw cookies, Authorization headers, bearer tokens, signed URLs, or raw shell commands. Secret scanning remains defensive even though previous phases should already provide redacted surfaces.

## UI

The existing Media inbox now includes a `Media queue telemetry` card after the dispatch control tower. It does not add `Queue`, `Telemetry`, `Pulse`, or any other new top-level route.

## Validation status

Per project direction, devtool validation is deferred until the final media validation phase. This overlay still includes a static validator and contract tests so the final phase has hooks to run.
