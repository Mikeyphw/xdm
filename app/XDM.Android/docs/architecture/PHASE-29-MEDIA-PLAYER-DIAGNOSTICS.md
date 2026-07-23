# Phase 29: Media Player 2.0 + Playback Diagnostics

Phase 29 upgrades the Media3 direct player card with structured diagnostics and playback-position planning.

## Scope

- Classify Media3 failures into source, network, decoder, unsupported codec, subtitle, protected-media, and unknown buckets.
- Keep protected-media handling diagnostic-only; no DRM bypass.
- Add retry-prepare guidance, track availability, subtitle availability, open library, open external, and refresh metadata actions.
- Plan local playback-position memory using the capture id, while refusing to persist when source redaction fails.
- Keep Media3 player diagnostics inside the existing Media route and Offline Library; no new top-level route and no Room migration.

Validation remains deferred until the final media release gate.
