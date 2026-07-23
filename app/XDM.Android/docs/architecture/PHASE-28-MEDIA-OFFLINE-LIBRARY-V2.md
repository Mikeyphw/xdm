# Phase 28: Media Offline Library 2.0

Phase 28 turns the Phase 20 offline library foundation into a filterable, sortable, sidecar-aware local media shelf.

## Scope

- Filter completed media by video, audio, failed, playable, needs cleanup, missing file, and source host.
- Sort by recent, title, duration, state, and source host.
- Detect missing files when a file-presence snapshot is available.
- Plan sidecar rename/remove actions without writing secrets.
- Plan open player, open folder, share, retry, resume, locate missing file, and thumbnail refresh actions.
- Export safe metadata only: no cookies, headers, bearer tokens, proxy credentials, or tokenized URLs.
- Keep this inside the existing Media route; no new top-level route and no Room migration.

Validation remains deferred until the final media release gate.
