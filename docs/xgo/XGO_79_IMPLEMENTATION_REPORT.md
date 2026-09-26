# XGO-79 Desktop download/media/browser UI reconnection

## Outcome

Avalonia and desktop browser/media transports are now adapters around the Go engine boundary instead of independent execution authorities.

## Download UI authority

- `DesktopGoProjectionClient` owns the desktop command/projection bridge.
- Add, pause, resume, cancel, and retry are submitted as `desktop.ui.download_command` commands.
- Go issues the narrow `desktop_download_command` platform request only after validating the requested action.
- `DesktopGoDownloadExecutionBroker` is transitional XGO-79 physical execution only. It delegates the explicitly authorized side effect to the legacy engine and contains no admission preview, retry, queue, priority, or scheduler policy.
- Legacy `ApplicationState` is observed by `DesktopGoExecutionObserver` and sent to Go as executor facts. `MainWindowViewModel` no longer renders it directly; visible downloads are rebuilt from `desktop.ui.projection`.
- A `desktop.ui.snapshot` restores the current Go projection when a desktop view reconnects.

The remaining legacy physical executor is intentionally deleted/migrated by XGO-80, not hidden inside this overlay.

## Browser/media reconnection

- Browser and manual media discovery still parse host formats, but the resulting evidence is converted to the versioned Go `CaptureEnvelope` plus capture/variant host records.
- Exact cookies and replay-only request metadata remain in `DesktopGoMediaBroker` handoffs. Go receives the transport URL evidence required by `CaptureEnvelope`/MediaGraph identity, while sensitive headers are excluded from the envelope and represented by an opaque credential-scope reference.
- Go builds the media graph, chooses the default execution selection, and emits `desktop.media.projection`.
- Avalonia rebuilds its media inbox and selected variants from that projection. Explicit variant changes are submitted back to Go with `desktop.media.select`.
- Media execution is submitted as `desktop.media.execute`; Go decides HLS/DASH/direct protocol and typed FFmpeg operation, then issues `external_media_tool`.
- The desktop broker accepts only `desktop_media_execute` + `ffmpeg` + `finalize_adaptive`/`record_stream`. It never accepts arbitrary argv or a shell.
- The old `IMediaDownloadService.DownloadAsync` path is no longer invoked by the normal Avalonia media command.
- Long FFmpeg platform work runs off the native frame-pump loop, matching the nonblocking Android host pattern.

## Validation

The `xgo_desktop` target now has seven nodes. `desktop_e2e` checks the command/projection cutover, CaptureEnvelope path, narrow execution brokers, nonblocking media dispatch, and absence of the old primary UI command/media-download calls.

Go runtime tests cover download observation/pause, add/resume/snapshot reopen, HLS typed FFmpeg execution, DASH typed FFmpeg execution, and Go default media selection. Existing desktop engine-host/publication/platform audits and the C ABI harness remain in the target.

## Deferred to XGO-80

XGO-80 owns one-time desktop legacy import and destructive removal/reduction of the old DownloadManager, segmented/recovery/queue/scheduler authorities, HLS/DASH downloaders, and duplicate aria2 ownership. XGO-79 deliberately does not claim that deletion seal.
