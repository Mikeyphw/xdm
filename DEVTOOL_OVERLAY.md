# XDM Android Phase 41 Browser Download Bridge Overlay

## Summary

Phase 41 builds on the landed Phase 40 browser tabs/session UX by turning WebView download events into a review-first Browser → Downloader handoff.

## Runtime changes

- Converts `WebView.setDownloadListener` events into a visible Browser download bridge draft.
- Uses `URLUtil.guessFileName(url, contentDisposition, mimeType)` to suggest a filename.
- Adds a `Download detected` Browser card with filename, MIME/type, size when known, source host, and source page.
- Adds primary `Add download` action that opens the existing Add Download flow with the detected URL and filename suggestion.
- Adds secondary `Inspect media` action for media-like direct files that should be reviewed in Media.
- Keeps dismiss local to Browser without queueing or starting transfers.

## Safety posture

- Browser downloads remain review-first and never auto-queue.
- The Browser UI does not call `addDownload` or start transfer execution directly.
- The WebView download listener no longer treats every direct file as a media capture.
- Cookies, tokens, and authorization headers are not displayed in the bridge card and are not persisted as raw browser handoff data.

## Deferred intentionally

- No file-type rule settings.
- No page-resource list.
- No browser download history/library screen.
- No bookmarks/history redesign.
- No full private-tab isolation.
- No transfer-engine changes.
- No media execution changes.
- No Room migration.
- No version bump.

## Validation

- Adds `tools/validate-phase-41-browser-download-bridge.py`.
- Wires Phase 41 into `tools/run-final-release-gate.sh`.
- Wires Phase 41 into Android CI static validators.
- Adds `ArchitectureContractTest.phaseFortyOneBrowserDownloadBridgeContractsArePresent`.
- Updates Phase 34 through Phase 40 validators/contracts to accept the Phase 41 current overlay.

## Packaging notes

- New files use inventory action `add`, not `create`.
- Archive excludes `__pycache__` and `.pyc` files.
- No new top-level route is added.


## Phase 42 Browser Media Capture Cockpit

- Adds a SuperX-style browser media cockpit on top of the Phase 41 Browser download bridge.
- Groups HLS, DASH, progressive, direct-file, audio, video, and unknown captures in the Browser surface.
- Shows a prominent Media found panel, selected capture summary, variant-count card, source diagnostics, and explicit Download selected / Resolve variants / Review media actions.
- Keeps direct file downloads in the Phase 41 Add Download bridge and keeps media execution review-first.
- Does not add routes, Room migrations, version bumps, transfer engine changes, or media execution changes.
