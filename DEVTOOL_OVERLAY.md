# XDM Android Phase 44 Browser Settings + Privacy Controls

Phase 44 adds browser-scoped settings and privacy controls after Phase 43 library surfaces.

## Changes

- Adds a Browser settings/privacy panel in the Browser session surface.
- Adds `BrowserPrivacySettings` persisted in `xdm_browser_sessions` SharedPreferences.
- Adds homepage selection and default search engine selection.
- Adds JavaScript, DOM storage, desktop-mode default, cookies, and third-party-cookie controls.
- Wires WebView settings through `BrowserPrivacySettings`.
- Adds clear browser data action for tabs, history, cache, DOM storage, and cookies while preserving bookmarks.
- Updates Phase 44 docs, manifest, validator, final release gate, Android CI, and ArchitectureContractTest coverage.

## Safety

- No new top-level route.
- No Room migration.
- No version bump.
- No transfer-engine changes.
- No media execution changes.
- No raw cookie/token/sensitive-header persistence.
- No silent auto-queue.

## Repair note

- Declares the local `openBrowserEntry` helper before `openHome` in `BrowserScreen.kt` so Kotlin can resolve the homepage shortcut at compile time.
- Strengthens the Phase 44 validator to guard that local-function ordering.
