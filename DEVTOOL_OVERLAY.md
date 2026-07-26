# XDM Android UIX R3 Overlay

UIX R3 installs the transfer-first Downloads control center and two-step Add workflow on top of the validated UIX R2 adaptive shell.

## Included

- Compact metrics for active transfers, aggregate speed, remaining time, and queued work.
- Active, Queued, Finished, and All filters with contextual empty states and an explicit search action.
- Flat download rows with long-press selection, one primary action, progress, reason, size, speed, and ETA.
- Adaptive detail presentation: bottom sheet on compact/medium screens and persistent list-detail pane on expanded layouts.
- Folded, redacted technical details.
- Organize downloads workspace for sorting, archive visibility, bulk actions, tags, saved searches, history, and Activity links.
- Two-step Add workflow: Review download, then Add to queue.
- External source review and explicit Inspect media that never auto-queues.
- JVM contracts, static validation, architecture documentation, CI, and final-gate integration.

## Preserved

Room remains schema 14 and the app remains `versionName 0.20.0-rc08` / `versionCode 21`. Internal routes, native, aria2, Termux, yt-dlp, Media3, queue intelligence, schedules, recovery, external handoff, and developer diagnostics are unchanged.

## Dependency

Apply after `xdm_android_uix_r2_flat_dark_adaptive_shell_overlay.zip`. UIX R4 will redesign Media and Library.
