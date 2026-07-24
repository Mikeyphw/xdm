# Phase 44: Browser Settings + Privacy Controls

Phase 44 adds browser-scoped settings and privacy controls on top of the Phase 43 Browser library surfaces.

## Goals

- Keep Browser and Downloader separated.
- Add homepage and default search engine controls.
- Add JavaScript, DOM storage, desktop-mode, cookie, and third-party-cookie controls.
- Add a clear browser data action for tabs, history, WebView cache, DOM storage, and cookies.
- Keep bookmarks intact when clearing transient browser data.
- Keep Browser settings in the existing `xdm_browser_sessions` SharedPreferences store.

## Runtime behavior

Browser settings are represented by `BrowserPrivacySettings` and persisted by `BrowserSessionStore`. The Browser settings panel is exposed inside the browser session card so it does not create new routes or mix with downloader settings.

The default profile remains usable for modern sites: JavaScript and DOM storage default on, cookies default on, and third-party cookies default off. Users can disable JavaScript or DOM storage when they want a stricter browsing posture. Desktop mode can be promoted to a default when mobile sites hide useful media resources.

The Private cookie profile remains a profile-level override: it rejects cookies, disables DOM storage, uses no-cache loading, and removes session cookies. Full private-tab isolation is still deferred to a later privacy phase.

## Clear browser data

The Clear browser data action resets the active browser session, removes tabs and history from the browser SharedPreferences store, clears WebView cache/history, removes cookies, deletes WebView DOM storage, and returns the browser to a blank start page. Bookmarks remain available because they are durable user library entries, not transient browsing data.

## Privacy guardrails

- No raw cookie, token, or sensitive header handoff data is persisted.
- Browser history remains separate from downloader history.
- Imported links and page resources remain review-first.
- No silent auto-queue is introduced.
- No Room migration is required.
- No new top-level route is added.

## Deferred

- Full private-tab isolation.
- Per-site permissions dashboard.
- Adblock, proxy-chain, encrypted DNS, and extension systems.
- Bookmark folders and import-from-file.
