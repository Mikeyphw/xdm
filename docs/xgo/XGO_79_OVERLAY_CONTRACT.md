# XGO-79 overlay contract

- Target: `xgo_desktop`
- Validation: required, no deferred validation
- Specialized validation job: `desktop_e2e`
- Boundary: desktop Avalonia/browser/media command and projection reconnection
- Legacy deletion: explicitly excluded; belongs to XGO-80
- Devtool bootstrap/install hooks: forbidden

## Required invariants

1. Add/pause/resume/cancel/retry cross Go before a desktop transfer side effect occurs.
2. Avalonia download presentation consumes `desktop.ui.projection`, not `IApplicationState` directly.
3. Legacy executor state is observation-only and cannot decide Go command semantics.
4. Browser/manual media discoveries become a versioned CaptureEnvelope and Go graph projection.
5. Sensitive request material remains in a desktop-local handoff; it is not placed in Go UI result events or diagnostics.
6. Media execution comes from a Go-authored typed `external_media_tool` request.
7. The desktop media broker has no arbitrary shell/argv interface and does not invoke the legacy HLS/DASH media downloader.
8. HLS, DASH, FFmpeg, command projection, and snapshot/reopen paths remain covered by focused validation.
