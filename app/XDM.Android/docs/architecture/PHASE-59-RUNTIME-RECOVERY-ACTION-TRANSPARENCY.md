# Phase 59 — Runtime Recovery Action Transparency

Phase59 builds on the Phase58 execution guard. Phase58 decides whether a recovery action can run now, must open Recovery Doctor, requires review, only gives guidance, or only copies a report. Phase59 makes those decisions visible before the user taps.

## Scope

- Adds a pure `RuntimeRecoveryActionPreviewPlanner` model.
- Adds Action preview rows to the Download details recovery card.
- Adds a safe action-preview section to copied recovery reports.
- Keeps all session/header/request data redacted.

## Safety boundary

Phase59 does not auto-start retries, switch methods in the background, delete files, request all-files access, add a top-level route, reopen Debug Workbench, persist browser context, or change Room schema. It only explains the already guarded action path.

## User-facing rule

Normal UI must show human labels such as `Explicit tap required`, `Recovery Doctor required`, `No background work`, and `Copy only`. It must not render raw URLs, Cookie values, Authorization values, bearer tokens, credential-bearing query values, raw enum names, or backend implementation details.
