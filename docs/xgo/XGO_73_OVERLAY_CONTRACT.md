# XGO-73 Android browser/media/FFmpeg reconnection contract

XGO-73 reconnects Android browser/media capture, media selection, and adaptive-media execution to the authoritative Go engine without performing XGO-74's durable Room import or XGO-75's legacy-code deletion.

## Authority boundary

- Extension/WebView capture may still be durably staged in legacy Room until XGO-74, but the process-scoped Android host must translate each mirrored capture to a sanitized `CaptureEnvelope` and submit it to Go.
- Go `MediaGraph` owns graph membership and selected-track projection used by the Android media inbox.
- HLS/DASH execution must enter Go before any legacy Kotlin media planner/dispatcher can run.
- Go may request only a typed `external_media_tool` operation. The request carries capture/variant identity and the allow-listed operation; it must not carry the exact transport URL, cookies, authorization headers, or arbitrary FFmpeg argv.
- Android resolves the exact encrypted `MediaRequestHandoff` locally and executes only the typed embedded-FFmpeg operation.
- Long-running FFmpeg work may not block the Go frame-pump thread; the Android platform dispatcher completes it asynchronously and returns the correlated platform reply when the operation finishes.

## Temporary transition surfaces

Room remains a transitional capture/variant source and selection persistence sink only until XGO-74. Legacy Kotlin media execution classes remain in the tree for non-adaptive compatibility and dependent cleanup, but HLS/DASH must short-circuit to Go before those paths. XGO-75 removes/reduces superseded authority after the import cutover.

## Validation

`xgo_android#validate` adds `android_media_e2e`. It must prove the live Go capture/selection/tool-request round trip and inspect the actual Android application, ViewModel, engine dispatcher, capture-envelope translation, and FFmpeg broker paths. Source-token presence in a detached helper is insufficient.
