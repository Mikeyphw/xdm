# Phase 12: external automation intake

Phase 12 gives Android XDM a durable command intake path for Tasker, browser, share-sheet, and view intents.

## Rules

- External commands are recorded in `automation_commands` before side effects.
- Stable idempotency keys prevent duplicate downloads and duplicate media captures.
- Tasker actions cover enqueue, capture media, pause all, and resume all.
- Share and view intents use capture-media behavior and stay within the existing Media route.
- Diagnostics may show automation command counts, but no new top-level route is introduced.
- Room schema 12 contains the command journal and has no unsupported top-level JSON keys.
