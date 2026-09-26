# XGO-73 implementation report

## Merge decision

XGO-73 is intentionally standalone. XGO-74 is a persistence/import authority migration, while XGO-75 is dependent legacy-authority deletion plus the Android seal. Merging either into the browser/media/FFmpeg reconnection would cross failure and ownership boundaries.

## Delivered

Android now mirrors persisted browser/WebView media captures through `AndroidMediaUiWire` as sanitized `CaptureEnvelope` inputs to the Go runtime. The Go Android-media state validates those envelopes, rebuilds the canonical `MediaGraph`, owns selected-track state, and emits the capture/variant/selection projection consumed by `MainViewModel` and Compose.

HLS/DASH download actions short-circuit at the start of `downloadMediaCapture`: Android submits the selection and execution command to Go and returns before the legacy Kotlin `MediaExecutionPlanner`/dispatcher path. Go validates graph membership, chooses the typed adaptive/live operation, and sends `external_media_tool` with capture/variant identity only.

`AndroidMediaPlatformBroker` resolves the exact encrypted request handoff locally, accepts only the allow-listed embedded-FFmpeg operations, and exposes no shell or arbitrary argv surface. `AndroidPlatformRequestDispatcher` runs that potentially long tool request on an IO scope so the JNI/event frame pump remains responsive while the correlated Go platform request is outstanding.

## Security behavior

Capture-envelope header translation strips authorization/cookie/proxy authorization/set-cookie/API-key names, any header containing `token`, and `*-key` names. Exact signed URLs and request headers remain Android-local in `MediaRequestHandoffStore`; Go tool requests contain no `source_url` or header payload.

## Deferred to XGO-74 / XGO-75

XGO-74 replaces the transitional Room capture/variant mirror and selection persistence write with the one-time versioned Go import and ends dual-write. XGO-75 then removes/reduces the superseded Kotlin media/scheduler/transfer/persistence authority and runs the Android legacy-authority seal. Non-adaptive legacy media compatibility code therefore remains present in XGO-73 by design.

## Validation evidence

The `xgo_android` workflow now has 10 nodes with `android_media_e2e` between `android_ui_smoke` and `android_host_runtime_tests`. The node performs a live capture -> Go projection -> selection -> typed FFmpeg platform request -> correlated result round trip and audits the real Android production path. Full `go test ./engine/...` and `go vet ./engine/...` also pass. Historical XAR10, XAR11, and XAR12 media validators remain green.

The older FF02/FF03/FF04 validators are already red on the post-XGO72 baseline: FF02/03 expect prior native-HLS ViewModel authority, and FF04 also has an unrelated pre-existing runtime-builder helper signature mismatch. XGO-73 does not absorb that unrelated toolchain repair.
