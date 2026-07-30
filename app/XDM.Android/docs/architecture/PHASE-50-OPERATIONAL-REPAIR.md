# Phase 50 Operational Repair

Phase 50 is a post-D7 field repair. It does not reopen the Debug Workbench D-series.

## Scope

- Repair Android runtime checkpoint parsing for native segmented downloads.
- Carry browser session headers only through process-local handoff when an external browser or Android download intent actually provides them.
- Add Referer and browser-like defaults to native metadata probes without bypassing site authentication.
- Make HTTP 401/403 metadata probe failures explain that the source session must be refreshed.
- Make public MediaStore completion more visible to file managers.

## Privacy boundary

Raw Cookie and Authorization values remain transient. They are not written to Room, sidecars, normal UI, or support bundles. Redacted summaries may say that browser session context was available.

## Android storage note

On Android 10 and newer, public files are committed through MediaStore, so the internal handle is a `content://` URI. This is expected scoped-storage behavior. Normal UI should show human destination labels, and completed files should be made visible by clearing pending state and notifying Android's media index.
