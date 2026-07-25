# Browser Removal Phase 6: Downloader UI Consolidation

## Purpose

Phase 6 gives the browser-free application an intentional downloader-first information architecture. It does not change transfer execution, persistence, or external handoff behavior.

## Stable destinations

- Downloads: transfer list and bulk actions.
- Add: review-first URL intake, available globally through the Add action.
- Media: media capture, resolver, track selection, and execution planning.
- Library: completed media, playback readiness, sidecar health, resume, and retry.
- Activity: overview plus Queues, Schedule, Recovery, and Diagnostics sub-sections.
- Settings: backend, storage, privacy-safe export, Termux, and automation configuration.

Compact layouts expose Downloads, Media, Library, Activity, and Settings in the bottom bar. Add remains a first-class route and is reachable from the global floating action button. Expanded layouts expose all six routes in the navigation rail.

## Compatibility

Persisted route names from the earlier topology migrate safely:

- Queues -> Activity
- Scheduler -> Activity
- Recovery -> Activity
- Diagnostics -> Activity

Unknown route names fall back to Downloads.

## Preservation boundary

Phase 6 preserves ExternalAddDownloadActivity, share and VIEW intake, Add Download classification, Inspect as media, native and aria2 engines, Termux adapters, workers, queue operations, recovery, diagnostics, offline media models, and Media3 playback.

Room remains schema 14. The Android version remains 0.20.0-rc08 with versionCode 21.
