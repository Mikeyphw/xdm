# Phase 24: Media Queue Actions

Phase 24 turns Phase 23 telemetry into an action-eligibility layer inside the existing Media route. It does not launch Android workers, Termux processes, aria2, or yt-dlp. The purpose is to make the UI truthful about what can be done next before the worker bridge lands.

## Actions

- Launch queue
- Pause media
- Resume media
- Retry media
- Cancel media
- Cleanup finished
- Refresh metadata
- Choose tracks
- Open Termux setup
- View diagnostics
- Open library

## Guardrails

- No new top-level route.
- No Room schema migration.
- No raw shell rendering.
- No cookie, Authorization, bearer token, signed query, or session value persistence.
- Destructive actions such as cancel and terminal cleanup require confirmation eligibility.
- Protected media remains diagnostic-only.
- Validation remains deferred until the final media validation phase; apply this overlay with `--no-validate`.
