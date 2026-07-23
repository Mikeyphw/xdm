# Phase 21: Media Download Engine Hardening

This phase hardens the media execution layer landed in Phase 20 without adding a new top-level route. It keeps the built-in browser, resolver, execution queue, offline library, and player inside the existing Media surface.

## Scope

- Remove the Phase 20 Kotlin warning caused by an unnecessary non-null assertion in `MediaCaptureServiceTest`.
- Add a pure Kotlin `MediaExecutionEnginePlan` that classifies resolver output into explicit lanes: `DirectNative`, `Aria2Segmented`, `YtDlpAdaptive`, `LiveRecording`, and `ProtectedBlocked`.
- Add Android-version-aware background policy modeling: user-initiated data-transfer ready on Android 14+, WorkManager foreground fallback, legacy foreground-service fallback, Termux external jobs, or blocked diagnostics.
- Model temporary Netscape cookie handoff files without persisting raw cookie values.
- Model aria2 transient input/session files without persisting raw headers or secrets.
- Attach cleanup actions to the process-local media handoff store.
- Add Media3 player error diagnostics and an explicit retry-prepare action for completed direct media.
- Add tests and contract sentinels for no cookie/header/token leaks across safe summaries, sidecars, transient plans, and typed executor arguments.

## Guardrails

- No direct code copy from `super-video-downloader`; this is a clean-room behavioral map.
- No DRM bypass; protected media remains diagnostic-only.
- No new top-level Android routes.
- No raw shell command rendering for yt-dlp or aria2.
- No persistent cookies, authorization headers, bearer tokens, signed URLs, or tokenized query strings in Room rows, sidecars, diagnostics, or logs.
- Devtool validation is intentionally deferred until the final phase per project direction; this overlay ships with validators and tests for the final release gate, but should be applied with `--no-validate`.

## Execution lanes

| Lane | Executor | Policy |
| --- | --- | --- |
| DirectNative | XDM native request | UIDT-ready / WorkManager / foreground-service fallback |
| Aria2Segmented | aria2 transient input/session | UIDT-ready / WorkManager / foreground-service fallback |
| YtDlpAdaptive | Termux yt-dlp | Termux external job |
| LiveRecording | Termux yt-dlp/FFmpeg | Termux external job |
| ProtectedBlocked | diagnostics only | blocked diagnostic |

## Cleanup contract

Every media request handoff records cleanup actions only, not secrets. Terminal execution must forget the process-local handoff, delete any temporary Netscape cookie file, delete aria2 transient input/session files, and verify persistent metadata stayed redacted.
