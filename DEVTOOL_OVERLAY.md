# XDM Android Phase 43 Browser Library Surfaces

Phase 43 adds browser-scoped library surfaces on top of the landed Phase 42 browser media capture cockpit.

## Scope

- Adds a Browser library card inside the first-class Browser surface.
- Adds browser bookmarks backed by the existing `xdm_browser_sessions` SharedPreferences store.
- Keeps recent browser history visible and browser-scoped.
- Adds page resources derived from observed browser requests and persisted media captures.
- Adds import links from pasted text.
- Adds clipboard paste import using Android `ClipboardManager`.
- Updates Phase 34 through Phase 42 validators for the new Phase 43 `current_overlay`.
- Adds Phase 43 static validator and ArchitectureContractTest coverage.

## Safety posture

- No new top-level route.
- No Room migration.
- No app version bump.
- No transfer-engine changes.
- No media execution changes.
- No silent auto-queue.
- Imported links and page resources remain review-first: Open in Browser or Add to downloader.
- Browser history remains separate from downloader history.

## Deferred

Private-tab full isolation, bookmark folders, full history management, import-from-file, encrypted browser library storage, and richer page-resource details are deferred to later phases.
