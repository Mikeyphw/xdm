# Phase 31: Session Privacy + Cleanup Audit

Phase 31 pressure-tests the media stack for secret persistence and transient handoff cleanup.

## Goals

- Scan browser profile, resolver handoff, queue specs, Room metadata, sidecars, logs, notifications, temp files, and Termux command previews.
- Classify findings as pass, review, or blocker.
- Require cleanup verification for transient cookie/input/session files after terminal job states.
- Keep durable surfaces free of cookies, headers, bearer tokens, tokenized URLs, proxy credentials, and raw command secrets.

## Guardrails

- Findings are redacted before UI/log exposure.
- Browser/session handoffs are treated as short-lived and process-local.
- Durable rows, sidecars, logs, and notifications must remain secret-safe.
- No new top-level route.
- No Room migration.
- Validation remains deferred until the final media gate.
