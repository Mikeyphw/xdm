# XDM Media Parity02 — Logical Media Capture & Browser Convergence

Date: 2026-09-11
Overlay: `xdm_media_parity02_logical_media_capture_browser_convergence_v2.zip`
Baseline: Media Parity01 as successfully applied by Devtool at commit `face04ca`
Validation policy: intermediate overlay; apply with `--no-validate`; Parity04 owns the exhaustive Android/device/release validation campaign.

## Purpose

Parity02 fixes the core media-sniffing mismatch demonstrated by the 1DM+ comparison: one logical HLS video must not be presented as dozens of segment downloads. It also makes Firefox and the in-app Live Locator WebView converge on the same logical-media semantics before Parity03 adds native adaptive execution.

## Delivered scope

### Shared logical media graph

- Added the browser-neutral `MediaObservation -> LogicalMedia -> Variant/Track` graph in `media/LogicalMediaGraph.kt`.
- The graph is bounded independently for logical items, retained evidence, aliases, and HLS child/master relationships.
- Repeated fetch/XHR/performance/DOM/network observations converge by canonical logical identity rather than becoming duplicate user-visible rows.
- Signed request refreshes keep exact execution URLs transient while canonical identity removes volatile credentials, including common token/signature/session parameters and AWS/GCP signing credentials.
- Non-secret query dimensions such as a quality selector remain part of canonical identity.
- HLS segments (`.ts`, `.m4s`, CMAF fragments), thumbnails/images, JSON/API noise, and repeated observer evidence remain internal evidence rather than normal media cards.
- Late HLS master discovery reparents previously observed child playlists into the master logical item.

### HLS structure, tracks, and protection taxonomy

- HLS master relationships are parsed sufficiently to retain video variants plus audio/subtitle tracks.
- Variant metadata carries bandwidth, resolution, codecs, and audio/subtitle group relationships.
- Track metadata carries type, group ID, display name, language, and default selection.
- AES-128 with identity key format is explicitly treated as encrypted media, not DRM, and remains a native candidate for Parity03.
- SAMPLE-AES/non-identity key formats are treated as protected/unsupported rather than silently attempted.
- LL-HLS markers (`EXT-X-PART`, `EXT-X-PRELOAD-HINT`, `EXT-X-SERVER-CONTROL`) produce an explicit fallback-required capability instead of partial native execution.

### Room 22 -> 23 persistence

- Room is now schema 23.
- `media_captures` carries logical identity, canonical media URL, observation/segment counts, protection/capability state, and logical confidence.
- Added the bounded `media_observations` table for privacy-redacted diagnostic evidence.
- Passive WebView observations are allowed before user admission; `captureId` is nullable so evidence does not violate foreign keys before a capture row exists.
- Evidence is related through logical-media identity and is pruned to a bounded retained set.
- Migration 22->23 backfills existing captures with stable legacy logical IDs and canonical source URLs.
- Exported Room schema 23 and Android migration regression coverage are included.

### Live Locator / WebView convergence

- Live Locator now feeds observations into `LogicalMediaGraphEngine` rather than mapping raw sniff results directly to user-facing cards.
- UI rows are keyed by `logicalMediaId`; repeated observers and segments update one logical row.
- Raw redacted evidence is persisted separately and exposed only under Developer diagnostics.
- The normal capture UI receives canonical media, variants, tracks, segment counts, confidence, and protection/capability state—not the raw network flood.

### Firefox logical chooser and direct-v3 handoff

- Firefox candidate storage now tracks logical media rather than a flat URL list.
- HLS child playlists/segments are absorbed under logical parents with late master promotion.
- Raw observer count is tracked separately from logical candidate count.
- Popup now presents a selectable logical-media list and an explicit `Send N to XDM` action before opening Android.
- Each chooser item exposes compact human-facing media details rather than silently sending an ambiguous number of raw URLs.
- Direct/keyless v3 handoff carries logical ID, canonical URL, manifest role, confidence, observation/segment counts, AES/protection/fallback state, video variant hints, and audio/subtitle track hints.
- Exact request URLs and sanitized privileged request headers remain separate execution evidence; canonical/persisted logical identity does not replace them.
- Android import prefers the Firefox logical-media ID, preserving the existing session/revision replay guard and preventing retransmission from minting duplicate captures.
- App-side import re-normalizes canonical identity and merges browser hints with resolver variants before persistence.

## Golden reproduction

The executable Kotlin smoke fixture reproduces the original 1DM+-style failure mode:

- HLS master playlist
- HLS child playlist
- many media segments
- poster JPEG
- repeated observations from multiple observer paths

Result: one user-visible logical HLS item, master manifest as canonical media, internal segment count, associated track/variant metadata, and no JPEG video row.

The local smoke output was:

`Parity02 logical graph smoke passed: 23 raw -> 1 logical; stress 1000 -> 1`

The stress fixture feeds 1,000 rotating signed observations into the graph and verifies one logical item with bounded retained evidence.

## Regression and validation evidence

Passed locally in the packaging environment:

- complete repository-owned Firefox Node test suite;
- Parity02 logical graph Kotlin compilation and executable golden/stress smoke;
- `tools/validate-media-parity02-logical-capture.py`;
- updated Parity01 carry-forward validator;
- complete canonical `tools/run-final-release-gate.sh --ci`;
- all 80 retained Phase-11 static validation-matrix rows.

The canonical static gate also replayed and passed the retained UX13/post-UX13, runtime foundation, media quality/privacy/final-gate, DL02/DL03, execution/media semantics, observability, thumbnail/MIME, Live Locator/naming, notification/WebView, and prior remediation contracts.

Full Gradle unit/lint/build/device validation is intentionally not claimed here. This is overlay 02 of 04 and is applied with `--no-validate`; Parity04 remains the exhaustive validation/release seal. The overlay nevertheless includes production unit/instrumentation contract sources so that final validation exercises this work instead of relying only on static text checks.

## Scope intentionally left for later overlays

Parity02 stops at correct capture identity, grouping, persistence, browser convergence, and capability classification. It does not claim native segmented HLS transfer/finalization, durable adaptive progress, final artifact verification, browser-shell redesign, userscript product UX, or the final cross-device release matrix. Those remain Parity03 and Parity04 scope.
