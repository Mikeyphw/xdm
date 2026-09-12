# FF02 — Embedded adaptive mux, HLS finalization, and verified post-processing

FF02 turns the FF01 embedded FFmpeg/FFprobe runtime into an app-owned media-processing pipeline. It does not make FFmpeg the generic downloader and it does not remove yt-dlp. Instead, XDM owns media work whenever the resolver already has concrete selected track URLs; yt-dlp remains the extractor boundary when those concrete URLs are not available.

## Runtime ownership

Execution priority for adaptive media is now:

1. Protected media remains diagnostic-only.
2. Live media uses `EmbeddedFfmpegLive`.
3. Simple supported HLS **media playlists** stay on the resumable native HLS segment engine.
4. HLS masters/DASH manifests with fully resolved selected tracks use `FfmpegAdaptive` / `EmbeddedFfmpegAdaptive`.
5. Incomplete or site-specific adaptive resolution falls back to `YtDlpAdaptive` in Termux.

The native HLS engine no longer treats master-playlist rendition URLs as if they were media segments. Masters with separate video/audio/subtitle renditions enter the embedded selected-track mux lane once those renditions are resolved.

## Adaptive selected-track execution

`MediaQueuedDownloadSpec` carries process-local `MediaSelectedTrackInput` values and an explicit `MediaPostProcessingPlan`. Track URLs and request headers are not copied into durable Room output records. Safe planner/diagnostic surfaces retain only redacted URLs and header names.

The embedded manager supports:

- video + audio muxing;
- video + audio + subtitle muxing;
- one-track adaptive finalization;
- audio-only remote extraction/finalization;
- live capture inherited from FF01;
- stream-copy first (`-c copy`) behavior;
- Android CA-store-backed verified HTTPS for every remote input.

FFmpeg-owned adaptive/video outputs use Matroska by default so a filename guessed before codec resolution cannot force an incompatible MP4 mux. Audio-only output uses M4A. No implicit transcoding is introduced by FF02.

## Progress and cancellation

All processing commands use FFmpeg's machine-readable `-progress pipe:1` protocol with `-nostats`. `FfmpegProgressParser` converts `out_time_us`, `total_size`, frame, and speed into bounded app progress snapshots.

`FfmpegProcessLauncher` polls the child process in a coroutine-cancellable loop. Cancellation destroys the FFmpeg process and escalates to forcible termination if it does not exit. The manager removes the unpublished staging artifact and records `Cancelled` rather than reporting a false success.

The Media card displays the actual embedded stage (`Preparing`, `Processing`, `Verifying`, `Publishing`, `Completed`) with percent/speed when FFmpeg reports them, and exposes Cancel for active embedded jobs.

## Verification and publication

A zero exit code is not sufficient for completion. FF02 runs FFprobe on the staged artifact and checks:

- minimum file size;
- at least one stream;
- required video/audio/subtitle streams for the selected operation;
- expected duration with bounded tolerance when known.

Only a verified artifact can enter `DestinationWriter.promote()` for durable adaptive/live output.

The reusable local `FfmpegPostProcessor` applies the same rule to remux, local video/audio mux, subtitle attach, audio extraction, MP4 faststart, and native-HLS finalization. It writes a sibling staging file, verifies it, then requires an atomic filesystem rename. Lack of atomic-rename support is an output failure; FF02 never exposes a partial replacement as the final file.

## Native HLS finalization

Native HLS remains responsible for media-playlist policy, retry/resume, encryption support, ordered part transfer, and progress. `NativeHlsFfmpegFinalizer` bridges its completed local part ledger into `FfmpegPostProcessor.finalizeNativeHls()`:

- complete local parts only;
- app-private ffconcat manifest;
- canonical path validation/escaping;
- stream-copy concat/remux;
- machine progress;
- FFprobe verification;
- atomic publication;
- transient concat/staging cleanup.

Separate-rendition HLS masters do not enter this simple one-rendition finalizer; they use the adaptive mux lane instead.

## Failure semantics

FF02 differentiates runtime/process/network/authentication/output/probe/verification failure and preserves recovery evidence where a staged durable output exists. Completed state is written only after probe verification and promotion. Runtime absence is surfaced as `NeedsEmbeddedFfmpegRuntime`, not as a Termux setup error.

## Validation ownership

Source contract: `tools/validate-ffmpeg02-media-mux-hls-postprocessing.py`

Gradle entrypoint: `:app:verifyFfmpeg02MediaMuxHlsPostprocessingContract`

The canonical release gate carries FF02 forward. FF02 is an intermediate full overlay and is applied with `--no-validate`; the final FF04 overlay owns full explicit Android validation.
