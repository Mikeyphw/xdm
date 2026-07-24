# Phase 49: Browser Download Rules + File-Type Interception

Phase 49 adds review-first browser download rules. XDM recognizes archive, APK, document, media, torrent, and unknown downloads, then recommends Add Download or media inspection without silently queueing.

## Contracts

- BrowserDownloadRules persists in existing browser SharedPreferences.
- BrowserDownloadRulesPanel exposes file-type rules.
- WebView download listener classifies downloads before showing the review card.
- Direct downloads still use the Phase 41 Add Download bridge.
- No transfer engine changes, no Room migration, no silent auto-queue.
