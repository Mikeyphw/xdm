# XDM Android XAR12 Media Execution Seal Report — 2026-09-13

## Overlay

- **Roadmap position:** overlay 12 of 17; merged roadmap overlay 3 of 8
- **Name:** XAR12 — Native HLS, Embedded FFmpeg & Termux Execution Seal
- **Target:** `xdm_android`
- **Archive:** `xdm_android_xar12_media_execution_seal_v2.tar.gz`
- **Commit metadata:** `XAR12: seal media execution ownership`

## Canonical coverage

XAR12 closes **23/23 S12 canonical findings**:

`S12-01`, `S12-02`, `S12-03`, `S12-04`, `S12-05`, `S12-06`, `S12-07`, `S12-08`, `S12-09`, `S12-10`, `S12-11`, `S12-12`, `S12-13`, `S12-14`, `S12-15`, `DS6-S12-01`, `DS6-S12-02`, `DS6-S12-03`, `DS6-S12-04`, `DS6-S12-05`, `DS6-S12-06`, `DS6-S12-07`, `RERUN56-S12-01`.

## Implementation summary

- Repaired managed Termux process ownership so legitimate `stdin-fifo` launched children are controllable by start-tick plus wrapper/process-group ownership, without accepting reused unrelated PIDs.
- Fenced Native HLS cancellation/interruption against committed publication evidence before deleting artifacts or downgrading state.
- Added a pure media execution security policy used by embedded FFmpeg and Termux fallback planning.
- Restricted Termux fallback to public HTTPS, headerless, non-private-network sessions.
- Scoped Native HLS sensitive headers by full origin: scheme, host, and effective port.
- Replaced unbounded Native HLS `body.bytes()` reads with bounded streaming reads tied to cancellable OkHttp calls.
- Validated byte-range HTTP 206 responses with exact `Content-Range` semantics.
- Used known HLS byte-range lengths in storage preflight.
- Made FFmpeg operations carry a wall-clock timeout and made missing FFprobe duration fail when an expected duration exists.
- Kept Termux transient artifacts app-private.
- Honored `EXT-X-GAP`, included `EXT-X-DISCONTINUITY-SEQUENCE` in durable part identity, reset implicit `EXT-X-BYTERANGE` offsets per resource, and emitted `EXT-X-MAP` init bytes only when needed.
- Rejected missing KEY URIs, malformed explicit AES-128 IVs, and segment URIs without preceding `EXTINF`.
- Wired the XAR12 validator into Gradle and `tools/run-final-release-gate.sh`.

## Validation performed in the packaging environment

- XAR01 build/signing/native provenance validator passed.
- XAR02 concurrency/CAS validator passed.
- XAR03 persistence generation integrity validator passed.
- XAR04 navigation/session ownership validator passed.
- XAR05 external intake admission validator passed.
- XAR06 storage publication validator passed.
- XAR07 native HTTP protocol validator passed.
- XAR08 aria2 ownership validator passed.
- XAR09 scheduler recovery validator passed.
- XAR10 browser capture evidence validator passed.
- XAR11 manifest resolution validator passed.
- XAR12 media execution seal validator passed.
- Python syntax checks passed.
- Shell final-gate syntax check passed.
- `PROJECT_MANIFEST.json` syntax check passed.
- Overlay inventory was re-extracted and SHA-256 verified.

A full Gradle execution was not run in this container because Gradle 9.7.1 is not available offline here. XAR12 remains an intermediate `--no-validate` overlay.

## XFE01 Firefox-extension invariant

XAR12 preserves the single-extension decision from XFE01. The Android Firefox extension remains the canonical working implementation; XAR12 does not modify production extension capture or handoff source. The only browser-extension file touched by this overlay is test coverage for the existing contract.
