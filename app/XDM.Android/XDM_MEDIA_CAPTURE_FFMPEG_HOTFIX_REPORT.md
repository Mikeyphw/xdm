# XDM media capture / embedded FFmpeg hotfix

## Scope

This hotfix addresses three coupled defects observed in the 2026-09-23 support bundle:

1. An HLS capture was accepted and queued but embedded FFmpeg failed immediately with `Invalid data found when processing input`.
2. Structured diagnostics stopped at the enqueue boundary, so the actual FFmpeg request, exit status, stderr context, verification and publication stages were invisible even with verbose logging.
3. The legacy single-capture browser compatibility path treated any request headers, plus broad expiry/checksum query markers, as replayable secrets and could reject otherwise safe URLs.

## Root-cause evidence

The support bundle records the captured URL as `application/vnd.apple.mpegurl` while its path ends in `index-v1-a1.txt`. XDM created one HLS variant and committed an `EmbeddedFfmpeg` / `AdaptiveFinalize` output. No structured event existed between that enqueue and the user-visible FFmpeg failure. The previous FFmpeg launcher also retained only the final non-blank stderr line, discarding earlier HTTP/TLS/demux evidence.

The browser single-capture compatibility path used `exactHeaders.isNotEmpty()` as part of its secret gate, so harmless request context such as User-Agent, Accept or Referer could trigger the same rejection class. The broad query detector intentionally treats expiry/checksum fields as privacy-sensitive for persistence and redaction, but that broad classifier was also being reused as a replay-credential admission gate.

## Changes

- Known HLS inputs now carry an explicit `-f hls` FFmpeg input hint, including playlists served through nonstandard extensions such as `.txt`.
- Exact selected-variant request headers are applied after inherited master/capture headers are origin-scoped. The XAR10 cross-origin credential boundary remains strict; target-specific secure handoff evidence can still reach the app-owned FFmpeg process.
- Embedded FFmpeg emits structured queue, prepare, request-context, execution, FFprobe verification, publication and terminal events. Verbose Trace events expose redacted input URLs, header names and input format decisions; failures are Error events even outside verbose mode.
- FFmpeg failures retain a redacted multi-line diagnostic tail and classify common HTTP 401/403 and 5xx failures more accurately.
- Browser intake emits a verbose candidate-context event showing whether final or proposed request evidence arrived.
- Legacy single-capture admission now distinguishes replayable credentials from expiry/checksum metadata. Real credential headers and signed/auth query parameters still fail closed and require the bounded Firefox v3 capture session.

## Validation performed in the repair workspace

Passed:

- `validate-ffmpeg01-embedded-runtime-media-execution.py`
- `validate-ffmpeg02-media-mux-hls-postprocessing.py --skip-prerequisites`
- `validate-media-session-privacy-audit.py`
- `validate-xar10-browser-capture-evidence.py`
- `validate-xar12-media-execution-seal.py`
- `validate-media-execution-library.py`
- `validate-media-final-validation-gate.py`
- `validate-media-parity04-browser-ux-release-seal.py`
- Kotlin compiler check for the changed FFmpeg runtime/compiler/launcher sources
- Kotlin compiler check for `AutomationModels.kt` with `ReleaseSecurityModels.kt`

The D1 and D4 debug-workbench standalone validators remain baseline-failing on the supplied snapshot for pre-existing manifest/source contract drift; the same failures reproduce in the pristine archive. They are not caused by this hotfix.

A full Gradle build was not available inside the repair sandbox because the Gradle 9.7.1 wrapper distribution is not cached and this environment has no network access. The overlay therefore keeps Devtool validation enabled so the target environment runs the configured XDM Android restore/build/test gate before commit.


## Audit loop v2

A post-packaging audit found one missing behavioral closure: a terminal embedded-FFmpeg output (`Failed`, `Cancelled`, or `RecoveryRequired`) left the Media card in `Ready` with the promise "You can download this media again", but primary admission still treated any non-hidden prior output as blocking. That could make the button return the old failed generation instead of starting a new one.

Fix applied in v2:

- Primary embedded-FFmpeg admission now blocks only active durable ownership states: `Queued`, `Active`, and `Completed`.
- Terminal states no longer trap the main Download button; they allow a fresh generation so the user-visible "download again" promise is truthful.
- Admission decisions now emit an `embedded-ffmpeg-admission` diagnostic when an active/completed generation blocks a duplicate primary enqueue.
- The hotfix contract test now asserts that `Failed`, `Cancelled`, and `RecoveryRequired` are not primary-admission blockers.
