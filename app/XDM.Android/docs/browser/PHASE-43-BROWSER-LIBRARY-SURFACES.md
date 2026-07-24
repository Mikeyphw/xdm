# Phase 43: Browser Library Surfaces

Phase 43 adds the first browser-scoped library surfaces on top of the Phase 42 Browser media capture cockpit. The goal is to make the Browser feel like the browser side of a dual Browser + Downloader app without mixing browser history with downloader history.

## Scope

- Browser library card in the Browser surface.
- Bookmarks saved in the browser session store.
- Recent browser history remains browser-scoped.
- Page resources list from observed/captured Browser requests.
- Import links from pasted text.
- Paste links from the Android clipboard into the import box.

## Product rules

- Downloader history and browser history stay separate.
- Imported links are review-first: users open them in Browser or send them to Add Download.
- Page resources are review-first and do not auto-queue.
- Direct downloads still use the Phase 41 Browser Download Bridge.
- Media captures still use the Phase 42 Media Capture Cockpit.

## Safety

Phase 43 does not add a new top-level route, Room migration, version bump, transfer-engine change, or media execution change. Bookmarks and library UI are backed by the browser `SharedPreferences` session store only.

## Deferred

Full private-tab isolation, encrypted library stores, bookmark folders, full history management, import-from-file, and page-resource network details are deferred to later phases.
