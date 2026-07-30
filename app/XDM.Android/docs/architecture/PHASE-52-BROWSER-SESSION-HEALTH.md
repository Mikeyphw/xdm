# Phase 52: Browser Session Health

Phase 52 adds a review-time Browser Session Health card to Add Download for external browser and extension handoffs.

## Goals

- Explain why protected sites may return HTTP 401/403 before a transfer starts.
- Show whether useful browser context was captured without exposing raw private values.
- Give the user a safe next action: refresh from browser, use captured session, inspect media first, or add the reviewed request.

## Normal UI privacy boundary

Normal UI may show:

- source site host label
- context captured/not captured
- sign-in context detected/not detected
- page context available/missing
- browser identity available/missing
- low/medium/high expiry risk
- suggested method and action labels

Normal UI must not show:

- Cookie values
- Authorization values
- bearer tokens
- credential-bearing query values
- full raw URLs
- raw header dumps
- raw enum names or machine states

## Storage and schema boundary

No Room migration is required. Phase 52 reuses the existing process-local session handoff from Phase 50 and only adds a safe report model plus Add Download copy.

## Debug Workbench boundary

The Debug Workbench D-series remains sealed. Phase 52 is an operational UX follow-up, not a D8 phase.
