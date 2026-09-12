# v6 unit-test compile closure

The authoritative Termux v5 run rolled back at `:media:compileDebugUnitTestKotlin` because `AdaptiveMediaExecutionMc03Test` used nonexistent `MediaNativeCapability.NativeHls`. The production capability enum is `Unknown | NativeCandidate | FallbackRequired | ProtectedUnsupported`; `NativeHls` is a `MediaDownloadStrategy`, not a capability. v6 changes the subtitle-only regression fixture to `MediaNativeCapability.NativeCandidate`, preserving the intended assertion that a native-capable HLS capture with subtitle-only intent must route to yt-dlp rather than native HLS media execution. FF02 and the post-seal gate now reject the invalid capability reference explicitly.

# XDM Android FF02 implementation report

## Promise

Deliver the second full embedded-FFmpeg overlay: selected adaptive track muxing, HLS finalization, reusable post-processing, stable progress, cancellation, atomic publication, FFprobe correctness, and truthful Media UI/job state. Do not introduce another mandatory Termux dependency and do not ship a contract-only scaffold.

## Delivered

- `FfmpegAdaptive` planner strategy and `EmbeddedFfmpegAdaptive` execution lane.
- Direct execution of fully resolved DASH/HLS rendition URLs with typed per-input headers and verified HTTPS.
- Video/audio/subtitle remote mux and audio-only extraction/finalization without shell commands.
- Conservative Matroska output for adaptive/video stream-copy jobs and M4A for audio-only jobs.
- `-progress pipe:1` parsing and lifecycle progress propagated through `EmbeddedFfmpegMediaManager` into Media cards.
- Coroutine-aware child-process cancellation and cleanup.
- FFprobe stream/size/duration validation before `DestinationWriter` promotion.
- Reusable staged/verified/atomic local remux, mux, subtitle, audio extraction, faststart, and native-HLS finalization service.
- Native HLS finalizer bridge for ordered completed media-playlist parts.
- HLS master/rendition routing correction: simple media playlists remain native; selected master renditions use embedded FFmpeg; unresolved cases preserve yt-dlp fallback.
- Explicit processing notice before queueing and embedded runtime Cancel action while processing.
- FF01 compatibility validator rebaselined only for the generalized FFmpeg output-container predicate.
- New FF02 source contract, focused tests, Gradle verification entrypoint, Devtool preflight carry-forward, and canonical final-gate registration.

## Security/privacy invariants retained

- No `sh -c`/arbitrary command-string execution.
- Captured authorization/cookie values stay process-local.
- Diagnostics and typed-plan previews contain redacted URLs/header values only.
- HTTPS peer verification uses FF01's Android CA trust bundle.
- Protected/DRM media remains diagnostic-only.

## Completion semantics

FFmpeg exit code 0 -> FFprobe -> semantic verification -> atomic promotion -> Completed.

Any probe/verification/publication failure does not become Completed. Cancellation terminates the runtime process and removes unpublished staging data.

## Validation policy

FF02 is an intermediate full overlay. Apply it with `--no-validate`; its tests/contracts are included now and the canonical gate carries them forward, while FF04 runs the complete explicit Android release validation set.

## 2026-09-12 post-seal roadmap audit correction

A post-v7r4 source-to-roadmap trace found that the original FF02 report overstated one integration point: `NativeHlsFfmpegFinalizer` existed but had no production caller. The same audit found ordinary completed-file FFprobe/fast-start/audio-extract/remux actions still inherited the Termux execution boundary.

The post-seal hotfix closes both gaps. `NativeHlsMediaManager` now owns supported media-playlist execution from durable part admission through embedded FFmpeg finalization, FFprobe verification and `AndroidDestinationWriter` publication, with pause/resume/cancel/recovery and encrypted request-context recovery. Ordinary completed-file FFmpeg/FFprobe actions are embedded-first; the explicit FF03 Termux fallback remains external through `externalFfmpegFallback`.

The FF02 validator now requires these production callers/owners, so the previous class-exists-without-call-site false positive cannot satisfy FF02 again.

## Post-seal third-pass FF02 closure v3

The third roadmap pass closes lifecycle gaps not covered by the original FF02 seal. Embedded adaptive/live publication now reconciles a canonical publication journal after process death, including exact-size proof for a provider item left at `DestinationCommitInProgress`. Cancellation after promotion cannot erase committed evidence. Audio-only embedded stream-copy chooses M4A only for AAC/MP4A and otherwise uses MKA. Native-HLS recovery identity now includes init-map BYTERANGE as well as its secret-safe URI, preventing stale fMP4 initialization reuse across refreshed playlists.

## Post-seal fourth-pass FF02 recovery closure v4

A state-transition audit found that embedded adaptive/live jobs had durable `RecoveryRequired` state but no executable Library Retry path: their `MediaOutputRecord` deliberately has no ordinary `downloadId`, while the UI retry callback only handled Termux jobs and Download-backed owners. v4 adds an embedded-only retry reconstruction path using durable selected-track/destination/output identity plus encrypted request handoffs, admits a new generation, and wires list/grid/details Retry actions to it. Native-HLS and completed-file post-processing keep their existing owner-specific retry paths. FF02 validation now fails if any embedded Retry surface becomes a dead action or if retry can silently switch to Termux.

## Post-seal compile closure v5

The authoritative v4 Devtool validation reached the full static gates, then failed `:media:compileDebugKotlin` because the new codec-aware container branch referenced `MediaVariantKind.Audio` without importing `MediaVariantKind`. The app-side native-HLS manager had the same latent omission and would have failed after the media module. v5 fixes both imports and makes FF02 validation assert compile-complete symbol ownership for these branches. No FF02 execution behavior was removed or downgraded.
## v7 authoritative compile closure

The v6 Termux validation reached `:app:compileDebugKotlin` and failed because `embeddedToolVersionsJson()` was a non-suspend helper calling suspend `EmbeddedFfmpegRuntime.capabilities()`. v7 makes that helper suspend; its existing callers already execute in `runEmbeddedMediaAction()`, which is suspend. FF02/post-seal validation now requires this signature explicitly. The v6 Devtool transaction rolled back successfully, so v7 remains cumulative directly over the post-v7r4 baseline.

