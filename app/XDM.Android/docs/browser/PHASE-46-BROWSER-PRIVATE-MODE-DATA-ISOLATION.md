# Phase 46: Browser Private Mode + Data Isolation

Phase 46 adds browser-scoped private mode and data-isolation guardrails on top of the Phase 45 Browser cockpit.

## Goals

- Add visible private tabs without adding a new route.
- Keep private tabs out of browser history.
- Keep private tabs out of restored-session persistence.
- Suppress passive media capture persistence while browsing privately.
- Give users a clear private-mode indicator and explicit Clear private action.
- Reject cookies and disable DOM storage for private tabs.
- Clear WebView session cookies when private tabs close or private state is cleared.

## Android WebView privacy posture

Android WebView privacy primitives are application-scoped rather than full browser-profile containers. XDM therefore treats Phase 46 private mode as an app-level isolation model:

- private tabs are not saved to `xdm_browser_sessions`;
- private page visits are not written to browser history;
- private passive media sniffing does not persist capture records;
- private tabs force the existing Private cookie profile;
- private WebView settings reject cookies and disable DOM storage;
- closing private tabs removes session cookies and flushes the cookie manager;
- bookmarks remain durable because saving one is an explicit user action.

This is intentionally not an adblock, proxy-chain, DNS, or extension phase.

## UX

The Browser session card now surfaces:

- New private tab;
- Clear private;
- active private-tab copy;
- retained private-tab count;
- Private prefix in the tab switcher;
- normal/private status in the visual status bar.

## Non-goals

- No new top-level route.
- No Room migration.
- No version bump.
- No transfer-engine changes.
- No media execution changes.
- No adblock/proxy/DNS behavior.
- No claim of perfect OS-level incognito isolation.
