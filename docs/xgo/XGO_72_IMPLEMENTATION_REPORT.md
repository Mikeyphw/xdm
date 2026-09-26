# XGO-72 — Android download UI and command reconnection

## Boundary

XGO-72 reconnects the Android download presentation/command surface to the single Go engine established by XGO-70/71. It does not prematurely perform XGO-74's persistence migration: Room is still the legacy durable source until that milestone imports it into Go.

To keep the UI contract stable across that transition, XGO-72 introduces one temporary host mirror behind the engine boundary:

`Room -> AndroidLegacyDownloadUiBroker -> android.ui.sync_downloads -> Go projection -> EngineService -> AndroidDownloadUiClient -> MainViewModel/Compose`

Download commands travel the opposite direction:

`Compose/ViewModel -> AndroidDownloadUiClient -> android.ui.download_command -> Go -> platform.request(android_download_command) -> AndroidLegacyDownloadUiBroker`

The temporary broker is deliberately outside the ViewModel. XGO-74 can remove its Room/runtime implementation after import without changing the UI-facing projection/command contract.

## Go projection and command protocol

The runtime now registers:

- `android.ui.sync_downloads`: accepts a complete legacy projection during the transition and emits `android.ui.projection`.
- `android.ui.download_command`: validates `add`, `pause`, `resume`, `cancel`, `retry`, and `delete`, sends the operation through the Go platform broker, and emits a correlated durable `android.ui.command_result`.

Projection state is mutex-protected. Monotonic projection revisions reject stale/out-of-order mirror commands instead of allowing an older Android snapshot to overwrite a newer Go projection.

The `android_download_command` platform kind is explicit in the runtime platform protocol; the Android dispatcher is the only route back to the temporary legacy executor.

## Android process/UI connection

`AndroidEngineProcessAuthority` owns the UI projection and pending command correlation alongside the existing single JNI engine. It consumes `android.ui.projection` and `android.ui.command_result` frames before forwarding unrelated frames.

`AndroidEngineService` exposes the process authority through a binder. `AndroidDownloadUiClient` is created once by `XdmApplication`, binds with `BIND_AUTO_CREATE`, and survives Activity/ViewModel recreation through the application container. It:

- keeps the last confirmed Go projection during disconnects;
- reports Connecting/Connected/Rebinding/Disconnected state;
- rebinds after service disconnect, binding death, or null binding;
- waits briefly for reconnection before rejecting a command with a typed `engine_disconnected` result.

The Downloads screen receives this connection state and displays an explicit reconnect notice while preserving the last confirmed projection.

## ViewModel command cutover

The main download list is sourced from `AndroidDownloadUiClient.projection`. The old final `live.progress` Kotlin rewrite has been removed, so Compose receives the Go download projection rather than a Go snapshot mutated again in the ViewModel.

The production command paths now cross Go for:

- add;
- pause / bulk pause / pause all;
- resume / start-now / bulk resume / resume all;
- retry for failed or recovery-required downloads;
- cancel;
- delete / clear finished history.

The Add flow preserves its prior request-safety ordering: the Go `add` command commits admission without starting execution; Android then durably records the exact request handoff, and a second Go `resume` command starts the download. This prevents a credential-bearing request from executing before its exact handoff context is durable.

Other download-management surfaces whose verbs are outside XGO-72 (archive, rename, source replacement, queue ordering, file deletion, redownload generation, etc.) remain for their later authority/migration cleanup and are not misrepresented as part of this milestone.

## XGO-74 transition adapter

`AndroidLegacyDownloadUiBroker` is explicitly temporary. It mirrors `repository.downloads` into Go and performs the old Room/native-runtime operations only after Go issues `platform.request(android_download_command)`.

This is not a second UI authority: MainViewModel no longer calls the broker, Room admission, queue start, native HLS pause/resume/cancel, or transfer-runtime pause/cancel directly for the XGO-72 command set. XGO-74 is responsible for replacing the broker's persistence side with Go's durable store and ending the mirror.

## Validation

`xgo_android#validate` gains the ninth node, `android_ui_smoke`, between `android_scheduler_authority` and `android_host_runtime_tests`.

The node performs a live Go runtime projection and command/platform-reply round trip, then source-traces the production Android path. It fails if:

- the download list stops consuming the Go projection;
- Kotlin live progress rewrites Go download state;
- add/pause/resume/cancel/retry/delete bypass Go from the audited ViewModel paths;
- reconnect code clears the last projection;
- the service/process authority stops carrying UI frames;
- the platform dispatcher no longer routes `android_download_command`;
- the Downloads route stops surfacing connection/rebind state.

XGO-73 remains separate and covers Android browser/media/FFmpeg reconnection.
