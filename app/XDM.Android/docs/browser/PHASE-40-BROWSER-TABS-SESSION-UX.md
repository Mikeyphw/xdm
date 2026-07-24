# Phase 40: Browser Tabs + Session UX

Phase 40 turns the Phase 39 browser chrome groundwork into explicit tab/session UX without expanding the browser into bookmarks, full history management, page-resource inventory, or a privacy model rewrite.

## Scope

Runtime browser UX changes:

- Model browser session state explicitly with `BrowserTabSessionState`.
- Show a browser-session summary near the browser chrome.
- Expose a tab switcher toggle instead of always crowding chips into the browser surface.
- Keep New tab and Close/Clear tab actions visible.
- Show a restored-session banner when prior tabs are loaded from the session store.
- Bound the compact visible tab list to keep phone UI usable.
- Keep private-tab isolation deferred to the later browser privacy phase.

## Product shape

The browser remains one first-class app surface beside the downloader. Phase 40 does not add new top-level routes. Tabs live inside Browser, not as separate app destinations.

```text
XDM Downloader
  Downloads / Add / Queues / Scheduler / Recovery / Diagnostics / Settings

XDM Browser
  Browser route
    Chrome
    Session summary
    Tab switcher
    WebView surface
    Media tray
```

## Current behavior after Phase 40

- Browser startup restores prior tab metadata from `BrowserSessionStore`.
- The active tab is summarized with title and URL/New tab state.
- Users can reveal or hide the tab switcher.
- Users can create a new blank tab.
- Users can close the active tab or clear a single active tab.
- A restored-session message is visible when tabs came from the previous browser session.
- Private browsing remains a cookie-profile behavior for now, not a fully isolated tab/session model.

## Deferred intentionally

Phase 40 does **not** add:

- Full bookmark manager.
- Full history redesign.
- Page resources screen.
- Import links from text files.
- WebView `saveState` / `restoreState` Bundle persistence.
- Full private tab isolation.
- Per-tab cookie jars.
- Transfer-engine changes.
- Media capture cockpit changes.
- Room migration.
- Version bump.

`WebView.saveState(Bundle)` / `restoreState(Bundle)` remain a later, careful phase because restoring WebView renderer state and history can be fragile across process death and profile changes. Phase 40 keeps a safer metadata-level tab restore: URL, title, active tab, and updated time.

## Validation checklist

- `PROJECT_MANIFEST.json` records `phase40_browser_tabs_session_ux` and current overlay `xdm_android_phase40_browser_tabs_session_ux_overlay.zip`.
- `BrowserScreen.kt` contains `BrowserTabSessionState` and `BrowserTabSwitcher`.
- The UI contains “Browser session”, “Show tabs”, “Hide tabs”, “Open tabs”, “New tab”, “Close tab”, and “Clear tab”.
- The UI mentions the deferred private-tab isolation posture.
- No new top-level `Tabs`, `History`, or `Bookmarks` route exists.
- Phase 40 validator is wired into the final release gate and Android CI.

## Contract phrases

- restored-session behavior is metadata-level only.
- private-tab isolation is deferred to the later privacy phase.
- No new top-level routes are introduced by this phase.
