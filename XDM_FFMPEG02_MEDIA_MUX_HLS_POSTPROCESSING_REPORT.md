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
