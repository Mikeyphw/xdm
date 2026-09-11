# XDM Media Parity03 — Native HLS Execution, Admission, Progress, Storage, and Completion Integrity

## Scope

Parity03 implements the native adaptive execution layer promised by the V2 roadmap after Parity02. It keeps the Parity02 logical-media capture graph as the source of truth, then turns a supported HLS logical item into one durable native segmented job instead of exposing or downloading the manifest and its transport parts as independent user downloads.

## Product contracts closed

- 118 internal parts -> one aggregate Download row. HLS parts are modeled as internal ledger rows under one `native_hls_jobs` aggregate and are not user-facing download cards.
- pause/resume/process-death recovery. Native jobs persist stage, finalization state, admission key, attempt generation, temp directory key, completed counts, retry metadata, and per-part state so recovery can resume from the ledger rather than restarting from a fragile UI list.
- low storage during segments and finalization. The native preflight estimates segment bytes plus finalization/mux/publish overhead and fails before corrupt native execution when space is insufficient; the ledger preserves resumable evidence for recovery after storage is freed.
- tiny/incoherent artifacts cannot become Completed. Completion verification rejects playlist text, HTML/error responses, too-small artifacts, unsupported plans, and hash mismatches before Completed can be emitted.
- unsupported LL-HLS routes to fallback. LL-HLS tags such as `EXT-X-PART`, `EXT-X-PRELOAD-HINT`, and `EXT-X-SERVER-CONTROL`, live playlists without `EXT-X-ENDLIST`, I-frame-only variants, missing segments, and invalid manifests use `NativeUnsupportedFallback` instead of half-running the native engine.
- AES-128 explicit and implicit IV. AES-128 HLS is native encrypted media, not DRM; explicit IVs are preserved and missing IVs derive from the media sequence. SAMPLE-AES, DRM key formats, and unknown encryption are protected unsupported.

## Implementation summary

`NativeHlsExecutionEngine` now negotiates support before admission, parses media playlists into native parts, records byte ranges, init maps, discontinuities, media sequence numbers, AES-128 key metadata, selected tracks, request headers for transient execution, and a canonical manifest identity from the Parity02 logical graph. `MediaDownloadPlanner` chooses `MediaDownloadStrategy.NativeHls` before yt-dlp when the capture is a supported VOD HLS candidate. Unsupported-but-valid HLS remains fallback instead of corrupt native execution.

Execution routing now has `MediaExecutionLane.NativeHlsSegmented`, a `native-hls` typed executor plan, native HLS progress signals, retry policy language for expired signed URLs/manifest refresh, and worker bridge dispatch that keeps the job app-side rather than misclassifying it as a direct native file download or a Termux-only job.

Room is advanced from schema 23 to schema 24 with `native_hls_jobs` and `native_hls_parts`. The job table stores aggregate stage/finalization/admission/progress/artifact state. The part table stores part URLs, byte ranges, init maps, AES-128 metadata, media sequence/discontinuity, retry state, bytes, and hashes. The `NativeHlsDao` provides active admission lookup, part listing, upserts, stage updates, part updates, and pruning for terminal jobs.

## Validation evidence in this overlay

The overlay adds `NativeHlsExecutionEngineTest` with executable Kotlin coverage for supported VOD HLS, AES-128 explicit/implicit IVs, byte ranges, init maps, track selection, idempotent admission, explicit Add again generation, progress truth, low-space preflight, LL-HLS fallback, protected SAMPLE-AES rejection, and completion verification. It also adds `MediaParity03NativeHlsExecutionContractTest` and `tools/validate-media-parity03-native-hls.py`, and wires the validator into `tools/run-final-release-gate.sh`.

## Boundaries

Parity03 intentionally does not finish the Parity04 UI surface. The light-browser shell redesign, Tampermonkey-style userscripts, floating media button/bottom sheet polish, notification action redesign, long filename/text wrapping audit, and exhaustive final device/Gradle release seal remain Parity04 work.
