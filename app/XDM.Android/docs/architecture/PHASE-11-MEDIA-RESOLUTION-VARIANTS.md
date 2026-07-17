# Phase 11 — Media Resolution and Variant Selection

Phase 11 turns captured media URLs into selectable download variants while preserving the existing Android topography.

## Guarantees

- No new top-level route was added. The existing Media route owns resolution and variant selection.
- HLS and DASH manifests resolve into persisted `media_variants` rows.
- Captures persist selected variant ID, selected variant URL, resolution status, last resolution time, and manifest expiry time.
- Expired playlist captures are marked for refresh before download.
- Media downloads use the selected variant URL when one is available.
- Resolution is retry-safe because variants are stored independently from the capture record.

## Recovery behavior

Startup recovery does not need to fetch network metadata. It can render the last persisted variants, selected variant, and refresh-required status. The user can refresh from the Media route before starting a stale playlist download.
