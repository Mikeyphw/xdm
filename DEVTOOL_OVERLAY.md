# XDM Android Phase 46 Browser Private Mode + Data Isolation

This overlay adds browser-scoped private mode and data-isolation guardrails on top of the Phase 45 Browser cockpit.

## Scope

- Add private tab state and visible private-mode copy.
- Exclude private tabs from browser history.
- Exclude private tabs from restored-session persistence.
- Suppress passive media capture persistence while browsing privately.
- Force private tabs through the existing Private cookie profile.
- Disable DOM storage and reject cookies while private.
- Clear WebView session cookies when private tabs close or private state is cleared.
- Preserve bookmarks and explicit review-first Add/Media handoffs.

## Non-goals

- No new route.
- No Room migration.
- No version bump.
- No transfer-engine changes.
- No media execution changes.
- No adblock/proxy/DNS changes.
- No claim of perfect browser-engine profile isolation.


Repair: Phase 46 current_overlay compatibility was added to older ArchitectureContractTest phase assertions so Phase 35 through Phase 45 contracts remain valid after the manifest advances to Phase 46. The Phase 46 validator now guards this allow-list rollover.
