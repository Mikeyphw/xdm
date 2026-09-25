# XGO-54+55+56 Implementation Report

## Scope

This overlay merges XGO-54, XGO-55, and XGO-56 because they are one coherent HLS pre-execution foundation:

- XGO-54: shared crash-safe fragment ledger for HLS/DASH media parts.
- XGO-55: HLS parser and protection taxonomy.
- XGO-56: stable HLS timeline identity and evolving playlist reconciliation.

XGO-57 remains separate because it owns actual fetch/decrypt/live execution.

## Delivered behavior

- Fragment identity includes protocol, timeline key, resource identity, byte range, and encryption identity.
- Fragment records carry role, state, expected length, hash, attempt generation, local artifact reference, and retry state.
- Fragment commit rejects stale attempts and enforces durable bytes before committed records.
- Duplicate fragment insertion is idempotent.
- HLS parser handles master/media playlists, variants, renditions, media/discontinuity sequence, `EXT-X-MAP`, `BYTERANGE`, `KEY`, `GAP`, `ENDLIST`, `TARGETDURATION`, and `PLAYLIST-TYPE`.
- URI references resolve relative to playlist URL.
- Protection taxonomy distinguishes clear, AES-128, SAMPLE-AES, DRM, and unknown methods.
- Timeline reconciliation retains completed known fragments, appends new fragments, marks evicted completed fragments historical, suppresses duplicates, distinguishes live/event/VOD, and handles ENDLIST transitions.

## Validation

- `fragment_faults`
- `hls_corpus`
- `hls_timeline_lab`
- `xgo_media` topology audit
- fixture lint and secret scan
- `go test ./engine/media ./engine/cmd/xgo-media-audit`
- `go vet ./engine/media ./engine/cmd/xgo-media-audit`
- full `go test ./engine/...`
- full `go vet ./engine/...`
