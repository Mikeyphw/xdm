# XGO-72 Overlay Contract

Target: `xgo_android`

Validation is required and may not be deferred. Do not apply this milestone with `--no-validate`.

The `android_ui_smoke` node must exercise the real Go projection/command round trip and inspect the production `MainViewModel`, process-scoped UI client, engine authority/service, Android platform dispatcher, compatibility broker, route, and Downloads screen.

Until XGO-74 performs the one-time Room import, legacy Room/runtime access is allowed only behind the Android platform broker. ViewModels may not use those paths for add/pause/resume/cancel/retry/delete, and the Downloads list may not overlay Kotlin live-progress state on top of the Go projection.

No Devtool reinstall, refresh, bootstrap, or source-install hook is permitted in this overlay.

XGO-73 browser/media/FFmpeg reconnection is intentionally excluded.
