# Phase 37B: Dual Launcher and Navigation Split

Phase 37B starts the runtime topology split promised by Phase 37A.

## What changed

- **Browser** is now a first-class top-level route.
- **XDM Browser** is a separate launcher activity.
- **XDM Downloader** remains the downloader launcher.
- **Add to XDM** remains the external share/browser download receiver from Phase 36.
- Compact navigation promotes Browser beside Downloads, Media, and Queues.
- Media is capture review/inbox and no longer hides the browser behind an Inbox/Browser chip.

## Deliberate limits

This phase does not claim to fix the WebView white-screen problem. That is Phase 38.

This phase does not add tabs, bookmarks, history UI, page resources, browser settings, adblock, proxy chains, DNS controls, or extension integration. Those are later roadmap phases.

## Safety posture

- No Room migration.
- No app version bump.
- No transfer-engine changes.
- No media execution changes.
- No raw shell exposure.
- No DRM bypass.
- No durable raw cookies/tokens/session/header persistence.
- Browser Activity is launcher-only in this phase; generic web/deep-link handling remains Phase 38+ work.

## User-facing topology

- **XDM Downloader**: Downloads, Add, Queues, Scheduler, Recovery, Diagnostics, Settings.
- **XDM Browser**: Browser screen, media request capture, and Add Download handoff from the browser screen.
- **Add to XDM**: explicit external download/share receiver.

Phase 38 must make the Browser reliable before adding bigger browser features.
