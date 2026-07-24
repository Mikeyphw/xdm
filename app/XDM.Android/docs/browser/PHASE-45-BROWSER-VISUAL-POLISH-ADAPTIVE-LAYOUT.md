# Phase 45: Browser Visual Polish + Adaptive Layout

Phase 45 polishes the Browser surface after the reliability, chrome, tabs, download bridge, media cockpit, library, and privacy phases have landed. It does not add another browser engine or a new route.

## Goals

- Make Browser feel like a first-class app surface, not a stack of debug panels.
- Center the browser cockpit on wide screens while keeping phone controls compact.
- Add a clear visual summary of tabs, media captures, page resources, bookmarks, profile, and desktop/mobile posture.
- Modernize start-page copy around search, homepage, downloads, media capture, and library recall.
- Keep direct downloads and media capture visually separate so users understand the Browser -> Downloader handoff.

## Runtime changes

- Browser content is constrained with `BrowserMaxContentWidthDp` and centered in the available surface.
- `BrowserVisualStatusBar` summarizes the cockpit without adding a route or drawer.
- Start-page language now describes the dual Browser/Downloader flow directly.
- Download bridge copy reinforces review-first Add Download behavior.
- Media cockpit copy reinforces compact review-first media selection.

## Non-goals

- No new top-level route.
- No Room migration.
- No app version bump.
- No transfer-engine changes.
- No media execution changes.
- No adblock, proxy, encrypted DNS, or extension system.
- No full private-tab isolation yet.

## Next slice

Phase 46 should handle power-user browser settings or private-tab isolation. Adblock/proxy/DNS should remain separate feature branches because they alter network behavior and permissions.
